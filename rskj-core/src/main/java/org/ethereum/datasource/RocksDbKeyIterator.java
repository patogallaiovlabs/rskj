/*
 * This file is part of RskJ
 * Copyright (C) 2018 RSK Labs Ltd.
 * (derived from ethereumJ library, Copyright (c) 2016 <ether.camp>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.ethereum.datasource;

import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.NoSuchElementException;

public class RocksDbKeyIterator implements DataSourceKeyIterator {
    private static final Logger logger = LoggerFactory.getLogger(RocksDbKeyIterator.class);
    private final ReadOptions readOptions;
    private final RocksIterator iterator;
    private boolean closed = false;

    public RocksDbKeyIterator(RocksDB db) {
        this.readOptions = new ReadOptions();
        this.iterator = db.newIterator(readOptions);
        this.iterator.seekToFirst();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        this.iterator.close();
        this.readOptions.close();
        closed = true;
    }

    @Override
    protected void finalize() throws Throwable {
        if (!closed) {
            logger.warn("Leaked native RocksIterator in RocksDbKeyIterator for {}", this);
            close();
        }
        super.finalize();
    }

    @Override
    public boolean hasNext() {
        return this.iterator.isValid();
    }

    @Override
    public byte[] next() throws NoSuchElementException {
        if (!this.hasNext()) {
            throw new NoSuchElementException();
        }

        byte[] key = this.iterator.key();

        this.iterator.next();

        return key;
    }
}
