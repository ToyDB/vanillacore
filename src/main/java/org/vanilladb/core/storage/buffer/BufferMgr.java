/*******************************************************************************
 * Copyright 2016 vanilladb.org
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.vanilladb.core.storage.buffer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.vanilladb.core.storage.file.BlockId;
import org.vanilladb.core.storage.tx.Transaction;
import org.vanilladb.core.storage.tx.TransactionLifecycleListener;
import org.vanilladb.core.util.CoreProperties;

/**
 * The publicly-accessible buffer manager. A buffer manager wraps a
 * {@link BufferPoolMgr} instance, and provides the same methods. The
 * difference is that the methods {@link #pin(BlockId)} and
 * {@link #pinNew(String, PageFormatter)} will never return false and null
 * respectively. If no buffers are currently available, then the calling thread
 * will be placed on a waiting list. The waiting threads are removed from the
 * list when a buffer becomes available. If a thread has been waiting for a
 * buffer for an excessive amount of time (currently, 10 seconds) then repins
 * all currently holding blocks by the calling transaction. Buffer manager
 * implements {@link TransactionLifecycleListener} for the purpose of unpinning buffers
 * when transaction commit/rollback/recovery.
 * 
 * <p>
 * A block must be pinned first before its getters/setters can be called.
 * </p>
 * 
 */
public class BufferMgr implements TransactionLifecycleListener {
	private static Logger logger = Logger.getLogger(BufferMgr.class.getName());
	
	protected static final int BUFFER_POOL_SIZE;
	private static final long MAX_TIME;
	private static final long EPSILON;

	static {
		MAX_TIME = CoreProperties.getLoader().getPropertyAsLong(BufferMgr.class.getName() + ".MAX_TIME", 10000);
		EPSILON = CoreProperties.getLoader().getPropertyAsLong(BufferMgr.class.getName() + ".EPSILON", 50);
		BUFFER_POOL_SIZE = CoreProperties.getLoader()
				.getPropertyAsInteger(BufferMgr.class.getName() + ".BUFFER_POOL_SIZE", 1024);
	}

	class PinnedBuffer {
		Buffer buffer;
		int pinnedCount = 1;

		PinnedBuffer(Buffer buffer) {
			this.buffer = buffer;
		}
	}

	protected static BufferPoolMgr bufferPool = new BufferPoolMgr(BUFFER_POOL_SIZE);
	protected static List<Thread> waitingThreads = new LinkedList<Thread>();

	private Map<BlockId, PinnedBuffer> pinnedBuffers = new HashMap<BlockId, PinnedBuffer>();
	private long txNum;
	
	public BufferMgr(long txNum) {
		this.txNum = txNum;
	}

	@Override
	public void onTxCommit(Transaction tx) {
		unpinAll(tx);
	}

	@Override
	public void onTxRollback(Transaction tx) {
		unpinAll(tx);
	}

	@Override
	public void onTxEndStatement(Transaction tx) {
		// do nothing
	}

	/**
	 * Pins a buffer to the specified block, potentially waiting until a buffer
	 * becomes available. If no buffer becomes available within a fixed time
	 * period, then repins all currently holding blocks.
	 * 
	 * @param blk
	 *            a block ID
	 * @return the buffer pinned to that block
	 */
	public Buffer pin(BlockId blk) {
		// Try to find out if this block has been pinned by this transaction
		PinnedBuffer pinnedBuff = pinnedBuffers.get(blk);
		if (pinnedBuff != null) {
			pinnedBuff.pinnedCount++;
			return pinnedBuff.buffer;
		}
		
		// This transaction has pinned too many buffers
		if (pinnedBuffers.size() == BUFFER_POOL_SIZE)
			throw new BufferAbortException();
		
		// Pinning process
		try {
			Buffer buff;
			long timestamp = System.currentTimeMillis();
			boolean waitedBeforeGotBuffer = false;

			// Try to pin a buffer or the pinned buffer for the given BlockId
			buff = bufferPool.pin(blk);

			// If there is no such buffer or no available buffer,
			// wait for it
			if (buff == null) {
				waitedBeforeGotBuffer = true;
				synchronized (bufferPool) {
					waitingThreads.add(Thread.currentThread());

					while (buff == null && !waitingTooLong(timestamp)) {
						bufferPool.wait(MAX_TIME);
						if (waitingThreads.get(0).equals(Thread.currentThread()))
							buff = bufferPool.pin(blk);
					}

					waitingThreads.remove(Thread.currentThread());
				}
			}

			// If it still has no buffer after a long wait,
			// release and re-pin all buffers it has
			if (buff == null) {
				repin();
				buff = pin(blk);
			} else {
				pinnedBuffers.put(buff.block(), new PinnedBuffer(buff));
			}

			// TODO: Add some comment here
			if (waitedBeforeGotBuffer) {
				synchronized (bufferPool) {
					bufferPool.notifyAll();
				}
			}

			return buff;
		} catch (InterruptedException e) {
			throw new BufferAbortException();
		}
	}

	/**
	 * Pins a buffer to a new block in the specified file, potentially waiting
	 * until a buffer becomes available. If no buffer becomes available within a
	 * fixed time period, then repins all currently holding blocks.
	 * 
	 * @param fileName
	 *            the name of the file
	 * @param fmtr
	 *            the formatter used to initialize the page
	 * @return the buffer pinned to that block
	 */
	public Buffer pinNew(String fileName, PageFormatter fmtr) {
		if (pinnedBuffers.size() == BUFFER_POOL_SIZE)
			throw new BufferAbortException();
		try {
			Buffer buff;
			long timestamp = System.currentTimeMillis();
			boolean waitedBeforeGotBuffer = false;

			// Try to pin a buffer or the pinned buffer for the given BlockId
			buff = bufferPool.pinNew(fileName, fmtr);

			// If there is no such buffer or no available buffer,
			// wait for it
			if (buff == null) {
				waitedBeforeGotBuffer = true;
				synchronized (bufferPool) {
					waitingThreads.add(Thread.currentThread());

					while (buff == null && !waitingTooLong(timestamp)) {
						bufferPool.wait(MAX_TIME);
						if (waitingThreads.get(0).equals(Thread.currentThread()))
							buff = bufferPool.pinNew(fileName, fmtr);
					}

					waitingThreads.remove(Thread.currentThread());
				}
			}

			// If it still has no buffer after a long wait,
			// release and re-pin all buffers it has
			if (buff == null) {
				repin();
				buff = pinNew(fileName, fmtr);
			} else {
				pinnedBuffers.put(buff.block(), new PinnedBuffer(buff));
			}

			// TODO: Add some comment here
			if (waitedBeforeGotBuffer) {
				synchronized (bufferPool) {
					bufferPool.notifyAll();
				}
			}

			return buff;
		} catch (InterruptedException e) {
			throw new BufferAbortException();
		}

	}

	/**
	 * Unpins the specified buffer. If the buffer's pin count becomes 0, then
	 * the threads on the wait list are notified.
	 * 
	 * @param buff
	 *            the buffer to be unpinned
	 */
	public void unpin(Buffer buff) {
		BlockId blk = buff.block();
		PinnedBuffer pinnedBuff = pinnedBuffers.get(blk);
		
		if (pinnedBuff != null) {
			pinnedBuff.pinnedCount--;
			
			if (pinnedBuff.pinnedCount == 0) {
				bufferPool.unpin(buff);
				pinnedBuffers.remove(blk);
				
				synchronized (bufferPool) {
					bufferPool.notifyAll();
				}
			}
		}
	}

	/**
	 * Flushes all dirty buffers.
	 */
	public void flushAll() {
		bufferPool.flushAll();
	}

	/**
	 * Flushes the dirty buffers modified by the specified transaction.
	 * 
	 * @param txNum
	 *            the transaction's id number
	 */
	public void flushAll(long txNum) {
		bufferPool.flushAll(txNum);
	}

	/**
	 * Returns the number of available (ie unpinned) buffers.
	 * 
	 * @return the number of available buffers`
	 */
	public int available() {
		return bufferPool.available();
	}

	private void unpinAll(Transaction tx) {
		// Copy the set of pinned buffers to avoid ConcurrentModificationException
		Set<PinnedBuffer> pinnedBuffs = new HashSet<PinnedBuffer>(pinnedBuffers.values());
		if (pinnedBuffs != null) {
			for (PinnedBuffer pinnedBuff : pinnedBuffs)
				bufferPool.unpin(pinnedBuff.buffer);
		}

		synchronized (bufferPool) {
			bufferPool.notifyAll();
		}
	}

	/**
	 * Unpins all currently pinned buffers of the calling transaction and repins
	 * them.
	 */
	private void repin() {
		if (logger.isLoggable(Level.WARNING))
			logger.warning("Tx." + txNum + " is re-pinning all buffers");
		
		try {
			// Copy the set of pinned buffers to avoid ConcurrentModificationException
			List<BlockId> blksToBeRepinned = new LinkedList<BlockId>();
			Map<BlockId, Integer> pinCounts = new HashMap<BlockId, Integer>();
			List<Buffer> buffersToBeUnpinned = new LinkedList<Buffer>();
			
			// Record the buffers to be un-pinned and the blocks to be re-pinned
			for (Entry<BlockId, PinnedBuffer> entry : pinnedBuffers.entrySet()) {
				blksToBeRepinned.add(entry.getKey());
				pinCounts.put(entry.getKey(), entry.getValue().pinnedCount);
				buffersToBeUnpinned.add(entry.getValue().buffer);
			}
			
			// Un-pin all buffers it has
			for (Buffer buf : buffersToBeUnpinned)
				unpin(buf);

			// Wait other threads pinning blocks
			synchronized (bufferPool) {
				bufferPool.wait(MAX_TIME);
			}

			// Re-pin all blocks
			for (BlockId blk : blksToBeRepinned)
				pin(blk);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	private boolean waitingTooLong(long startTime) {
		return System.currentTimeMillis() - startTime + EPSILON > MAX_TIME;
	}
}
