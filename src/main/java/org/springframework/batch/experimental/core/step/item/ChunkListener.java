package org.springframework.batch.experimental.core.step.item;

import org.springframework.batch.core.StepListener;
import org.springframework.batch.item.Chunk;

/*
 * The current org.springframework.batch.core.ChunkListener uses ChunkContext which is passed as parameter to chunk listener methods.
 * In the new implementation, this context is not used (it is part of the repeat package, which is not used here).
 * Therefore, it makes more sense to pass the chunk of items to the listener's methods (consistent with item listeners).
 *
 * Notable difference: afterChunk is called inside the transaction, not outside the transaction.
 */
public interface ChunkListener<I, O> extends StepListener {

	/**
	 * Callback before the chunk is processed, inside the transaction.
	 */
	default void beforeChunk(Chunk<I> chunk) {
	}

	/**
	 * Callback after the chunk is processed, inside the transaction.
	 */
	default void afterChunk(Chunk<O> chunk) {
	}

	/**
	 * Callback if an exception occurs while processing a chunk, inside the transaction,
	 * which is about to be rolled back. As a result, you should use {@code PROPAGATION_REQUIRES_NEW}
	 * for any transactional operation that is called from here.</em>
	 *
	 * @param exception the exception that caused the underlying rollback.
	 * @param chunk     the processed chunk
	 */
	default void onChunkError(Exception exception, Chunk<O> chunk) {
	}

}