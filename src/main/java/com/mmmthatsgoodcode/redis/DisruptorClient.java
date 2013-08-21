package com.mmmthatsgoodcode.redis;

import io.netty.channel.ChannelOption;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.hash.HashFunction;
import com.lmax.disruptor.BatchEventProcessor;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.SleepingWaitStrategy;
import com.lmax.disruptor.WaitStrategy;
import com.mmmthatsgoodcode.redis.Client.Builder;
import com.mmmthatsgoodcode.redis.client.Transaction;
import com.mmmthatsgoodcode.redis.disruptor.processor.RequestEvent;
import com.mmmthatsgoodcode.redis.disruptor.processor.RequestHasher;
import com.mmmthatsgoodcode.redis.disruptor.processor.RequestRouter;
import com.mmmthatsgoodcode.redis.protocol.PendingResponse;
import com.mmmthatsgoodcode.redis.protocol.Request;
import com.mmmthatsgoodcode.redis.protocol.Response;
import com.mmmthatsgoodcode.redis.protocol.request.Exec;
import com.mmmthatsgoodcode.redis.protocol.response.MultiBulkResponse;

public class DisruptorClient extends Client {

	public static class Builder extends Client.Builder<DisruptorClient> {
		
		public final static int MIN_PROCESSING_BUFFER_SIZE = 2*Runtime.getRuntime().availableProcessors();
		private int processingBufferSize = 1024;
		private WaitStrategy processingWaitStrategy = new SleepingWaitStrategy();

		public Builder() {
			
		}
		
		public Builder withProcessingBufferSize(int processingBufferSize) {
			if (processingBufferSize < MIN_PROCESSING_BUFFER_SIZE) throw new IllegalArgumentException("Processing buffer size may not be smaller than "+MIN_PROCESSING_BUFFER_SIZE);
			this.processingBufferSize = processingBufferSize;
			return this;
		}
		
		public Builder withProcessingWaitStrategy(WaitStrategy processingWaitStrategy) {
			if (processingWaitStrategy == null) throw new IllegalArgumentException("Processing wait strategy may not be null");
			this.processingWaitStrategy = processingWaitStrategy;
			return this;
		}
		
		public DisruptorClient build() {
			// add some default channel options
			if (!channelOptions.containsKey(ChannelOption.SO_KEEPALIVE)) channelOptions.put(ChannelOption.SO_KEEPALIVE, true);
			if (!channelOptions.containsKey(ChannelOption.CONNECT_TIMEOUT_MILLIS)) channelOptions.put(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000);
			
			return new DisruptorClient(hosts, hashFunction, connectionsPerHost, channelOptions, shouldHash, connectionRecovery, processingWaitStrategy, processingBufferSize, monitors, withTrafficLogging);

			
		}
		
	}
	
	protected final RingBuffer<RequestEvent> processingBuffer;
	protected ExecutorService processors = Executors.newFixedThreadPool(2);

	
	protected DisruptorClient(List<HostInfo> hosts, HashFunction hashFunction,
			int connectionsPerHost, Map<ChannelOption, Object> channelOptions,
			AtomicBoolean shouldHash, boolean connectionRecovery,
			WaitStrategy processingWaitStrategy, int processingBufferSize,
			List<ClientMonitor> monitors, AtomicBoolean trafficLogging) {
		super(hosts, hashFunction, connectionsPerHost, channelOptions, shouldHash,
				connectionRecovery,
				monitors, trafficLogging);
		
		// create processing buffer
		processingBuffer = RingBuffer.createMultiProducer(RequestEvent.EVENT_FACTORY, processingBufferSize, processingWaitStrategy);

		// create processors
		BatchEventProcessor<RequestEvent> hasher = new BatchEventProcessor<RequestEvent>( processingBuffer, processingBuffer.newBarrier(), new RequestHasher(this ) );
		BatchEventProcessor<RequestEvent> router = new BatchEventProcessor<RequestEvent>( processingBuffer, processingBuffer.newBarrier(hasher.getSequence()), new RequestRouter(this));
		
		// start processors
		processors.execute(hasher);
		processors.execute(router);
		
		processingBuffer.addGatingSequences(router.getSequence());

		
	}

	public <T extends Response> PendingResponse<T> send(Request<T> request) {
		
		processingBuffer.publishEvent(new RequestEvent.RequestEventTranslator(request));
		return request.getResponse();
		
	}
	
	public PendingResponse<MultiBulkResponse> send(Transaction transaction) {
		
		// close transaction with EXEC
		Exec exec = new Exec();
		transaction.add(exec);
		
		processingBuffer.publishEvent(new RequestEvent.RequestEventTranslator(transaction));
		return exec.getResponse();
		
	}
	
	
}
