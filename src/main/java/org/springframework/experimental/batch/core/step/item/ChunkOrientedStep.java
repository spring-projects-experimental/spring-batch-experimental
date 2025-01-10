/*
 * Copyright 2023-2025 the original author or authors.
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
package org.springframework.experimental.batch.core.step.item;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.batch.core.ItemProcessListener;
import org.springframework.batch.core.ItemReadListener;
import org.springframework.batch.core.ItemWriteListener;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.AbstractStep;
import org.springframework.batch.core.step.FatalStepExecutionException;
import org.springframework.batch.core.step.StepInterruptionPolicy;
import org.springframework.batch.core.step.ThreadStepInterruptionPolicy;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemStream;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.support.CompositeItemStream;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

/**
 * Step implementation for the chunk-oriented processing model.
 *
 * @author Mahmoud Ben Hassine
 * @param <I> type of input items
 * @param <O> type of output items
 */
public class ChunkOrientedStep<I, O> extends AbstractStep {

	private static final Log logger = LogFactory.getLog(ChunkOrientedStep.class.getName());

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
	 * Transactional related parameters
	 */
	private final TransactionTemplate transactionTemplate;
	private final TransactionAttribute transactionAttribute = new DefaultTransactionAttribute() {

		@Override
		public boolean rollbackOn(Throwable ex) {
			return true;
		}

	};

	/*
	 * Chunk related parameters
	 */
	private final int chunkSize;
	private final ChunkTracker chunkTracker = new ChunkTracker();
	private ChunkListener<I, O> chunkListener = new ChunkListener<>() {};

	/*
	 * Step state / interruption parameters
	 */
	private final CompositeItemStream stream = new CompositeItemStream();
	private StepInterruptionPolicy interruptionPolicy = new ThreadStepInterruptionPolicy();


	public ChunkOrientedStep(String name, int chunkSize,
							 ItemReader<I> itemReader, ItemProcessor<I, O> itemProcessor, ItemWriter<O> itemWriter,
							 JobRepository jobRepository, PlatformTransactionManager transactionManager) {
		super(name);
		Assert.isTrue(chunkSize > 0, "Chunk size must be greater than 0");
		setJobRepository(jobRepository);
		this.itemReader = itemReader;
		this.itemProcessor = itemProcessor;
		this.itemWriter = itemWriter;
		this.transactionTemplate = new TransactionTemplate(transactionManager, this.transactionAttribute);
		this.chunkSize = chunkSize;
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

	public ChunkOrientedStep(String name, int chunkSize,
							 ItemReader<I> itemReader, ItemWriter<O> itemWriter,
							 JobRepository jobRepository, PlatformTransactionManager transactionManager) {
		this(name, chunkSize, itemReader, item -> (O) item, itemWriter, jobRepository, transactionManager);
	}

	@Override
	protected void doExecute(StepExecution stepExecution) throws Exception {
		while (this.chunkTracker.moreItems()) {
			this.interruptionPolicy.checkInterrupted(stepExecution);
			this.transactionTemplate.execute(new TransactionCallbackWithoutResult() {
				@Override
				protected void doInTransactionWithoutResult(TransactionStatus status) {
					StepContribution contribution = stepExecution.createStepContribution();
					Chunk<I> inputChunk = new Chunk<>();
					Chunk<O> processedChunk = new Chunk<>();
					try {
						inputChunk = read(contribution);
						chunkListener.beforeChunk(inputChunk);
						processedChunk = process(inputChunk, contribution);
						write(processedChunk, contribution);
						chunkListener.afterChunk(processedChunk);
						stepExecution.apply(contribution);
						stepExecution.incrementCommitCount();
						stream.update(stepExecution.getExecutionContext());
						getJobRepository().update(stepExecution);
						getJobRepository().updateExecutionContext(stepExecution);
					} catch (Exception e) {
						logger.error("Rolling back chunk transaction", e);
						status.setRollbackOnly();
						stepExecution.incrementRollbackCount();
						chunkListener.onChunkError(e, processedChunk);
						throw new FatalStepExecutionException("Unable to process chunk", e);
					}
				}
			});
		}
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

	public void registerStream(ItemStream stream) {
		this.stream.register(stream);
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

	public void setChunkListener(ChunkListener<I, O> chunkListener) {
		this.chunkListener = chunkListener;
	}

	public void setTransactionAttribute(TransactionAttribute transactionAttribute) {
		this.transactionTemplate.setIsolationLevel(transactionAttribute.getIsolationLevel());
		this.transactionTemplate.setPropagationBehavior(transactionAttribute.getPropagationBehavior());
		this.transactionTemplate.setTimeout(transactionAttribute.getTimeout());
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


}
