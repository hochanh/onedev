package com.gitplex.server.manager.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gitplex.launcher.loader.Listen;
import com.gitplex.server.event.lifecycle.SystemStarted;
import com.gitplex.server.event.lifecycle.SystemStopping;
import com.gitplex.server.manager.BatchWorkManager;
import com.gitplex.server.manager.WorkExecutor;
import com.gitplex.server.util.BatchWorker;
import com.gitplex.server.util.concurrent.Prioritized;
import com.gitplex.server.util.concurrent.PrioritizedRunnable;

@Singleton
public class DefaultBatchWorkManager implements BatchWorkManager, Runnable {

	private static final Logger logger = LoggerFactory.getLogger(DefaultBatchWorkManager.class);
	
	private final WorkExecutor workExecutor;
	
	private final Map<BatchWorker, Works> works = new HashMap<>();
	
	private Thread thread;
	
	@Inject
	public DefaultBatchWorkManager(WorkExecutor workExecutor) {
		this.workExecutor = workExecutor;
	}
	
	private Works getWorks(BatchWorker worker) {
		Works worksOfWorker = works.get(worker);
		if (worksOfWorker == null) {
			worksOfWorker = new Works();
			works.put(worker, worksOfWorker);
		}
		return worksOfWorker;
	}

	@Listen
	public void on(SystemStarted event) {
		thread = new Thread(this);
		thread.start();
	}

	@Listen
	public synchronized void on(SystemStopping event) {
		thread = null;
		notify();
	}

	@Override
	public synchronized void run() {
		while (thread != null) {
			for (Iterator<Map.Entry<BatchWorker, Works>> it = works.entrySet().iterator(); it.hasNext();) {
				Map.Entry<BatchWorker, Works> entry = it.next();
				BatchWorker worker = entry.getKey();
				Works works = entry.getValue();
				if (works.working.isEmpty()) {
					works.queued.drainTo(works.working, worker.getMaxBatchSize());
					if (!works.working.isEmpty()) {
						double priority = works.working.stream().collect(Collectors.averagingInt(Prioritized::getPriority));
						workExecutor.execute(new PrioritizedRunnable((int)priority) {
							
							@Override
							public void run() {
								try {
									worker.doWorks(works.working);
								} catch (Exception e) {
									logger.error("Error doing works", e);
								} finally {
									synchronized(DefaultBatchWorkManager.this) {
										works.working.clear();
										DefaultBatchWorkManager.this.notify();
									}
								}
							}
							
						});
					} else {
						it.remove();
					}
				}
			}
			try {
				wait();
			} catch (InterruptedException e) {
			}
		}
	}

	@Override
	public synchronized void submit(BatchWorker worker, Prioritized work) {
		getWorks(worker).queued.offer(work);
		notify();
	}

	private static class Works {
		BlockingQueue<Prioritized> queued = new PriorityBlockingQueue<>();
		
		Collection<Prioritized> working = new ArrayList<>();
	}

}
