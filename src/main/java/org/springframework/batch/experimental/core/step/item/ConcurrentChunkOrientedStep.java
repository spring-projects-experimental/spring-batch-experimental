/*
 * Copyright 2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.batch.experimental.core.step.item;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.ItemProcessListener;
import org.springframework.batch.core.ItemReadListener;
import org.springframework.batch.core.ItemWriteListener;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.AbstractStep;
import org.springframework.batch.core.step.StepInterruptionPolicy;
import org.springframework.batch.core.step.ThreadStepInterruptionPolicy;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemStream;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.support.CompositeItemStream;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

/**
 * Concurrent implementation for the chunk-oriented processing model.
 *
 * @author Mahmoud Ben Hassine
 * @param <I> type of input items
 * @param <O> type of output items
 */
public class ConcurrentChunkOrientedStep<I, O> extends AbstractStep {

	private static final Log logger = LogFactory.getLog(ConcurrentChunkOrientedStep.class.getName());

	/*
	 * Step Input / Output parameters
	 */
	private final ItemReader<I> itemReader;
	private ItemReadListener<I> itemReadListener = new ItemReadListener<>() {};

	private final ItemProcessor<I, O> itemProcessor;
	private ItemProcessListener<I, O> itemProcessListener = new ItemProcessListener<>() {};

	private final ItemWriter<O> itemWriter;
	private ItemWriteListener<O> itemWriteListener = new ItemWriteListener<>() {};

	/*
	 * Chunk related parameters
	 */
	private final int chunkSize;
	private final ChunkTracker chunkTracker = new ChunkTracker();
	private ChunkListener<O> chunkListener = new ChunkListener<>() {};

	/*
	 * Step state / interruption parameters
	 */
	private final CompositeItemStream stream = new CompositeItemStream();
	private StepInterruptionPolicy interruptionPolicy = new ThreadStepInterruptionPolicy();

	private final Set<Future<StepContribution>> stepContributions = new HashSet<>();
	private final AsyncTaskExecutor taskExecutor = new SimpleAsyncTaskExecutor("worker-thread-");

	private final ChunkProcessor chunkProcessor;

	public ConcurrentChunkOrientedStep(String name, int chunkSize,
									   ItemReader<I> itemReader, ItemProcessor<I, O> itemProcessor, ItemWriter<O> itemWriter,
									   JobRepository jobRepository, PlatformTransactionManager transactionManager) {
		super(name);
		Assert.isTrue(chunkSize > 0, "Chunk size must be greater than 0");
		setJobRepository(jobRepository);
		this.itemReader = itemReader;
		this.itemProcessor = itemProcessor;
		this.itemWriter = itemWriter;
		this.chunkSize = chunkSize;
		this.chunkProcessor = new ChunkProcessor(itemProcessor, itemWriter, new TransactionTemplate(transactionManager));
		if (this.itemReader instanceof ItemStream itemStream) {
			this.stream.register(itemStream);
		}
		if (this.itemProcessor instanceof ItemStream itemStream) {
			this.stream.register(itemStream);
		}
		if (this.itemWriter instanceof ItemStream itemStream) {
			this.stream.register(itemStream);
		}
	}

	public ConcurrentChunkOrientedStep(String name, int chunkSize,
									   ItemReader<I> itemReader, ItemWriter<O> itemWriter,
									   JobRepository jobRepository, PlatformTransactionManager transactionManager) {
		this(name, chunkSize, itemReader, item -> (O) item, itemWriter, jobRepository, transactionManager);
	}

	@Override
	protected void doExecute(StepExecution stepExecution) throws Exception {
		// prepare chunks and submit them to the task executor
		while (this.chunkTracker.moreItems()) {
			this.interruptionPolicy.checkInterrupted(stepExecution);
			StepContribution contribution = stepExecution.createStepContribution();
			Chunk<I> chunk = read(contribution);
			Callable<StepContribution> task = () -> chunkProcessor.processChunk(chunk, contribution);
			stepContributions.add(taskExecutor.submit(task));
		}
		// wait for results
		List<StepContribution> contributions = new ArrayList<>();
		for (Future<StepContribution> task : stepContributions) {
			contributions.add(task.get());
		}
		// apply contributions
		for (StepContribution contribution : contributions) {
			System.out.println("apply contribution "+ contribution);
			stepExecution.apply(contribution);
		}
		getJobRepository().update(stepExecution);
		System.out.println("step execution: " + stepExecution);
	}

	private Chunk<I> read(StepContribution contribution) throws Exception {
		Chunk<I> chunk = new Chunk<>();
		for (int i = 0; i < chunkSize; i++) {
			this.itemReadListener.beforeRead();
			try {
				I item = itemReader.read();
				if (item == null) {
					chunkTracker.noMoreItems();
					break;
				} else {
					chunk.add(item);
					contribution.incrementReadCount();
					this.itemReadListener.afterRead(item);
				}
			} catch (Exception exception) {
				this.itemReadListener.onReadError(exception);
				throw exception;
			}

		}
		return chunk;
	}

	private Chunk<O> process(Chunk<I> chunk, StepContribution contribution) throws Exception {
		Chunk<O> processedChunk = new Chunk<>();
		for (I item : chunk) {
			try {
				this.itemProcessListener.beforeProcess(item);
				O processedItem = this.itemProcessor.process(item);
				this.itemProcessListener.afterProcess(item, processedItem);
				if (processedItem == null) {
					contribution.incrementFilterCount(1);
				} else {
					processedChunk.add(processedItem);
				}
			} catch (Exception exception) {
				this.itemProcessListener.onProcessError(item, exception);
				throw exception;
			}
		}
		return processedChunk;
	}

	private void write(Chunk<O> chunk, StepContribution contribution) throws Exception {
		try {
			this.itemWriteListener.beforeWrite(chunk);
			this.itemWriter.write(chunk);
			contribution.incrementWriteCount(chunk.size());
			this.itemWriteListener.afterWrite(chunk);
		} catch (Exception e) {
			this.itemWriteListener.onWriteError(e, chunk);
			throw e;
		}

	}

	@Override
	protected void open(ExecutionContext executionContext) throws Exception {
		this.stream.open(executionContext);
	}

	@Override
	protected void close(ExecutionContext executionContext) throws Exception {
		this.stream.close();
	}

	/**
	 * Checked at chunk boundaries. Defaults to {@link ThreadStepInterruptionPolicy}.
	 */
	public void setInterruptionPolicy(StepInterruptionPolicy interruptionPolicy) {
		this.interruptionPolicy = interruptionPolicy;
	}


	public void setItemReadListener(ItemReadListener<I> itemReadListener) {
		this.itemReadListener = itemReadListener;
	}

	public void setItemProcessListener(ItemProcessListener<I, O> itemProcessListener) {
		this.itemProcessListener = itemProcessListener;
	}

	public void setItemWriteListener(ItemWriteListener<O> itemWriteListener) {
		this.itemWriteListener = itemWriteListener;
	}

	public void setChunkListener(ChunkListener<O> chunkListener) {
		this.chunkListener = chunkListener;
	}

	private static class ChunkTracker {
		private boolean moreItems = true;

		void noMoreItems() {
			this.moreItems = false;
		}

		boolean moreItems() {
			return this.moreItems;
		}
	}

	private class ChunkProcessor {

		ItemProcessor<I, O> itemProcessor;

		ItemWriter<O> itemWriter;

		TransactionTemplate transactionTemplate;

		public ChunkProcessor(ItemProcessor<I, O> itemProcessor, ItemWriter<O> itemWriter, TransactionTemplate transactionTemplate) {
			this.itemProcessor = itemProcessor;
			this.itemWriter = itemWriter;
			this.transactionTemplate = transactionTemplate;
		}

		public ChunkProcessor(ItemWriter<O> itemWriter, TransactionTemplate transactionTemplate) {
			this(item -> (O) item, itemWriter, transactionTemplate);
		}

		StepContribution processChunk(Chunk<I> chunk, StepContribution contribution) {
			return this.transactionTemplate.execute(status -> {
				Chunk <O> processedChunk = new Chunk<>();
				try {
					System.out.println(Thread.currentThread().getName() + ": ChunkProcessor.processChunk " + chunk);
					chunkListener.beforeChunk();
					processedChunk = process(chunk, contribution);
					write(processedChunk, contribution);
					contribution.setExitStatus(ExitStatus.COMPLETED);
					chunkListener.afterChunk(processedChunk);
				} catch (Exception e) {
					logger.error("Rolling back chunk transaction", e);
					status.setRollbackOnly();
					contribution.setExitStatus(ExitStatus.FAILED.addExitDescription(e));
					chunkListener.onChunkError(e, processedChunk);
				}
				return contribution;
			});

		}
	}


}
