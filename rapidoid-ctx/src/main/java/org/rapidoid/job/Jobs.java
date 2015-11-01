package org.rapidoid.job;

/*
 * #%L
 * rapidoid-ctx
 * %%
 * Copyright (C) 2014 - 2015 Nikolche Mihajlovski and contributors
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.rapidoid.activity.RapidoidThreadFactory;
import org.rapidoid.annotation.Authors;
import org.rapidoid.annotation.Since;
import org.rapidoid.concurrent.Callback;
import org.rapidoid.config.Conf;
import org.rapidoid.ctx.Ctx;
import org.rapidoid.ctx.Ctxs;
import org.rapidoid.ctx.JobStatusListener;

@Authors("Nikolche Mihajlovski")
@Since("4.1.0")
public class Jobs {

	private static ScheduledExecutorService SCHEDULER;

	private static Executor EXECUTOR;

	private Jobs() {}

	public static synchronized ScheduledExecutorService scheduler() {
		if (SCHEDULER == null) {
			int threads = Conf.option("threads", 100);
			SCHEDULER = Executors.newScheduledThreadPool(threads / 2, new RapidoidThreadFactory("jobs"));
		}

		return SCHEDULER;
	}

	public static synchronized Executor executor() {
		if (EXECUTOR == null) {
			int threads = Conf.option("threads", 100);
			EXECUTOR = Executors.newFixedThreadPool(threads);
		}

		return EXECUTOR;
	}

	public static ScheduledFuture<?> schedule(Runnable job, long delay, TimeUnit unit) {
		return scheduler().schedule(wrap(job), delay, unit);
	}

	public static <T> ScheduledFuture<?> schedule(Callable<T> job, long delay, TimeUnit unit, Callback<T> callback) {
		return schedule(callbackJob(job, callback), delay, unit);
	}

	public static ScheduledFuture<?> scheduleAtFixedRate(Runnable job, long initialDelay, long period, TimeUnit unit) {
		return scheduler().scheduleAtFixedRate(wrap(job), initialDelay, period, unit);
	}

	public static <T> ScheduledFuture<?> scheduleAtFixedRate(Callable<T> job, long initialDelay, long period,
			TimeUnit unit, Callback<T> callback) {
		return scheduleAtFixedRate(callbackJob(job, callback), initialDelay, period, unit);
	}

	public static ScheduledFuture<?> scheduleWithFixedDelay(Runnable job, long initialDelay, long delay, TimeUnit unit) {
		return scheduler().scheduleWithFixedDelay(wrap(job), initialDelay, delay, unit);
	}

	public static <T> ScheduledFuture<?> scheduleWithFixedDelay(Callable<T> job, long initialDelay, long delay,
			TimeUnit unit, Callback<T> callback) {
		return scheduleWithFixedDelay(callbackJob(job, callback), initialDelay, delay, unit);
	}

	public static void execute(Runnable job) {
		executor().execute(wrap(job));
	}

	public static <T> void execute(Callable<T> job, Callback<T> callback) {
		execute(callbackJob(job, callback));
	}

	public static Runnable wrap(Runnable job) {
		Ctx ctx = Ctxs.get();

		if (ctx != null) {
			// U.notNull(ctx.app(), "Application wasn't attached to the context!");

			Object x = ctx.exchange();

			if (x instanceof JobStatusListener) {
				((JobStatusListener) x).onAsync();
			}

			// increment reference counter
			ctx = ctx.span(); // currently the same ctx is returned
		}

		return new ContextPreservingJobWrapper(job, ctx);
	}

	public static <T> void callIfNotNull(Callback<T> callback, T result, Throwable error) {
		if (callback != null) {
			Jobs.execute(new CallbackExecutorJob<T>(callback, result, error));
		}
	}

	public static <T> void call(Callback<T> callback, T result, Throwable error) {
		Jobs.execute(new CallbackExecutorJob<T>(callback, result, error));
	}

	private static <T> Runnable callbackJob(final Callable<T> job, final Callback<T> callback) {
		return new Runnable() {
			@Override
			public void run() {
				T result;

				try {
					result = job.call();
				} catch (Throwable e) {
					call(callback, null, e);
					return;
				}

				call(callback, result, null);
			}
		};
	}

}
