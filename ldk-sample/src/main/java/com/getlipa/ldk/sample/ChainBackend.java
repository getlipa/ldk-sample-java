package com.getlipa.ldk.sample;

import java.util.Arrays;
import java.util.Objects;

public interface ChainBackend {

    class BlockHeader {
        public final int height;
        public final byte[] data;

        public BlockHeader(final int height, final byte[] data) {
            this.height = height;
            this.data = data;
        }
    }

    class TxBlockInfo {
        public final Block block;
        public final byte[] data;
        public final byte[] id;

        public TxBlockInfo(final byte[] blockHash, final int blockHeight, final long index, final byte[] data, final byte[] id) {
            this.id = id;
            this.block = new Block(blockHash, blockHeight);
            this.data = data;
        }
    }

    class Block {
        public int height;
        public final byte[] hash;

        public Block(final byte[] hash, final int height) {
            this.hash = hash;
            this.height = height;
        }

        @Override
        public boolean equals(final Object other) {
            if (this == other){
                return true;
            }
            if (other == null || getClass() != other.getClass()){
                return false;
            }
            final Block block = (Block) other;
            return height == block.height && Arrays.equals(hash, block.hash);
        }

        @Override
        public int hashCode() {
            return Objects.hash(height, hash);
        }
    }

    boolean isSynced();

    int blockHeight();

    int blockHeight(byte[] blockHash);

    byte[] blockHash(int height);

    byte[] blockHeader(byte[] hash);

    void publish(byte[] transaction);

    boolean isConfirmed(byte[] txid);

    TxBlockInfo determineBlockInfo(byte[] txid);

    long determineBlockIndex(byte[] blockHash, byte[] txid);

}
