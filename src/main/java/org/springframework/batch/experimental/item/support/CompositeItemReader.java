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
package org.springframework.batch.experimental.item.support;

import java.util.Iterator;
import java.util.List;

import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamReader;

/**
 * Composite reader that delegates reading to a list of {@link ItemStreamReader}s.
 * This implementation is not thread-safe and not restartable.
 *
 * @author Mahmoud Ben Hassine
 * @param <T> type of objects to read
 */
public class CompositeItemReader<T> implements ItemStreamReader<T> {

	private final List<ItemStreamReader<T>> delegates;

	private final Iterator<ItemStreamReader<T>> delegatesIterator;

	private ItemStreamReader<T> currentDelegate;

	public CompositeItemReader(List<ItemStreamReader<T>> delegates) {
		this.delegates = delegates;
		this.delegatesIterator = this.delegates.iterator();
		this.currentDelegate = this.delegatesIterator.hasNext() ? this.delegatesIterator.next() : null;
	}

	@Override
	public void open(ExecutionContext executionContext) throws ItemStreamException {
		for (ItemStreamReader<T> delegate : delegates) {
			delegate.open(executionContext);
		}
	}

	@Override
	public T read() throws Exception {
		if (this.currentDelegate == null) {
			return null;
		}
		T item = currentDelegate.read();
		if (item == null) {
			currentDelegate = this.delegatesIterator.hasNext() ? this.delegatesIterator.next() : null;
			return read();
		}
		return item;
	}

	@Override
	public void close() throws ItemStreamException {
		for (ItemStreamReader<T> delegate : delegates) {
			delegate.close();
		}
	}
}