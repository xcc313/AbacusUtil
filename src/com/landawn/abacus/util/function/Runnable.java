/*
 * Copyright (C) 2018 HaiYang Li
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.landawn.abacus.util.function;

import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Try;

/**
 * 
 * @since 1.2
 * 
 * @author Haiyang Li
 */
public interface Runnable extends java.lang.Runnable, Try.Runnable<RuntimeException> {

    @Override
    void run();

    /**
     * Returns the specified instance
     * 
     * @param runnable
     * @return
     */
    public static Runnable of(final Runnable runnable) {
        N.checkArgNotNull(runnable);

        return runnable;
    }

    public static <R> Runnable create(final Callable<R> callable) {
        N.checkArgNotNull(callable);

        return new Runnable() {
            @Override
            public void run() {
                callable.call();
            }
        };
    }
}
