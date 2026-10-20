/*
 * This file is part of RskJ
 * Copyright (C) 2026 RSK Labs Ltd.
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
package co.rsk.jmh.crypto;

import org.bouncycastle.crypto.digests.KeccakDigest;
import org.ethereum.crypto.HashUtil;
import org.openjdk.jmh.annotations.*;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Throughput of {@link HashUtil#keccak256(byte[])} (sphlib-derived KeccakCore) against
 * BouncyCastle's {@link KeccakDigest} on input sizes seen during block processing.
 *
 * Run with: ./gradlew :rskj-core:jmh -PjmhArgs="co.rsk.jmh.crypto.Keccak256Benchmark"
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class Keccak256Benchmark {

    @Param({"32", "136", "137", "4096", "65536"})
    public int size;

    private byte[] input;
    private final KeccakDigest bc = new KeccakDigest(256);
    private final byte[] bcOut = new byte[32];

    @Setup
    public void setup() {
        input = new byte[size];
        new Random(42).nextBytes(input);
    }

    @Benchmark
    public byte[] hashUtil() {
        return HashUtil.keccak256(input);
    }

    @Benchmark
    public byte[] bouncyCastle() {
        bc.update(input, 0, input.length);
        bc.doFinal(bcOut, 0);
        return bcOut;
    }
}
