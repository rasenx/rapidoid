package org.rapidoid.net.impl;

/*
 * #%L
 * rapidoid-net
 * %%
 * Copyright (C) 2014 - 2015 Nikolche Mihajlovski
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

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;

import org.rapidoid.buffer.BufGroup;
import org.rapidoid.buffer.IncompleteReadException;
import org.rapidoid.pool.ArrayPool;
import org.rapidoid.pool.Pool;
import org.rapidoid.util.Conf;
import org.rapidoid.util.SimpleList;
import org.rapidoid.util.U;

public class RapidoidWorker extends AbstractEventLoop<RapidoidWorker> {

	private final Queue<RapidoidConnection> restarting;

	private final Queue<ConnectionTarget> connecting;

	private final Queue<RapidoidChannel> connected;

	private final SimpleList<RapidoidConnection> done;

	private final Pool<RapidoidConnection> connections;

	private final int maxPipelineSize;

	final Protocol protocol;

	final RapidoidHelper helper;

	private final boolean isProtocolListener;

	private final int bufSize;

	private final boolean noDelay;

	private final BufGroup bufs;

	public RapidoidWorker(String name, final BufGroup bufs, final Protocol protocol, final RapidoidHelper helper,
			int bufSizeKB, boolean noNelay) {
		super(name);
		this.bufs = bufs;

		this.protocol = protocol;
		this.helper = helper;
		this.maxPipelineSize = Conf.option("pipeline-max", Integer.MAX_VALUE);

		final int queueSize = Conf.micro() ? 1000 : 1000000;
		final int growFactor = Conf.micro() ? 2 : 10;

		this.restarting = new ArrayBlockingQueue<RapidoidConnection>(queueSize);
		this.connecting = new ArrayBlockingQueue<ConnectionTarget>(queueSize);
		this.connected = new ArrayBlockingQueue<RapidoidChannel>(queueSize);
		this.done = new SimpleList<RapidoidConnection>(queueSize / 10, growFactor);

		this.isProtocolListener = protocol instanceof CtxListener;

		connections = new ArrayPool<RapidoidConnection>(new Callable<RapidoidConnection>() {
			@Override
			public RapidoidConnection call() throws Exception {
				return newConnection();
			}
		}, 100000);

		this.bufSize = bufSizeKB * 1024;
		this.noDelay = noNelay;
	}

	public void accept(SocketChannel socketChannel) throws IOException {

		configureSocket(socketChannel);

		connected.add(new RapidoidChannel(socketChannel, false));
		selector.wakeup();
	}

	public void connect(ConnectionTarget target) throws IOException {

		configureSocket(target.socketChannel);

		connecting.add(target);

		if (target.socketChannel.connect(target.addr)) {
			U.debug("Opened socket, connected", "address", target.addr);
		} else {
			U.debug("Opened socket, connecting...", "address", target.addr);
		}

		selector.wakeup();
	}

	private void configureSocket(SocketChannel socketChannel) throws IOException, SocketException {
		socketChannel.configureBlocking(false);

		Socket socket = socketChannel.socket();
		socket.setTcpNoDelay(noDelay);
		socket.setReceiveBufferSize(bufSize);
		socket.setSendBufferSize(bufSize);
		socket.setReuseAddress(true);
	}

	@Override
	protected void connectOP(SelectionKey key) throws IOException {
		SocketChannel socketChannel = (SocketChannel) key.channel();

		ConnectionTarget target = (ConnectionTarget) key.attachment();

		boolean ready;
		try {
			ready = socketChannel.finishConnect();
			U.failIf(!ready, "Expected an established connection!");
			connected.add(new RapidoidChannel(socketChannel, true));
		} catch (ConnectException e) {
			retryConnecting(target);
		}
	}

	private void retryConnecting(ConnectionTarget target) throws IOException {
		U.debug("Reconnecting...", "address", target.addr);
		target.socketChannel = SocketChannel.open();
		target.retryAfter = U.time() + 1000;
		connect(target);
	}

	@Override
	protected void readOP(SelectionKey key) throws IOException {
		SocketChannel socketChannel = (SocketChannel) key.channel();
		RapidoidConnection conn = (RapidoidConnection) key.attachment();

		int read;
		try {
			read = conn.input.append(socketChannel);
		} catch (Exception e) {
			read = -1;
		}

		if (read == -1) {
			// the other end closed the connection
			U.debug("The other end closed the connection!");

			if (conn.isClient()) {
				InetSocketAddress addr = conn.getAddress();
				close(key);
				retryConnecting(new ConnectionTarget(null, addr));
			} else {
				close(key);
			}

			return;
		}

		process(conn);
	}

	public void process(RapidoidConnection conn) {
		processMsgs(conn);

		conn.completedInputPos = conn.input.position();
	}

	private int processMsgs(RapidoidConnection conn) {
		int reqN = 0;

		while (reqN < maxPipelineSize && conn.input().hasRemaining() && processNext(conn)) {
			reqN++;
		}

		return reqN;
	}

	private boolean processNext(RapidoidConnection conn) {
		int pos = conn.input().position();
		int limit = conn.input().limit();
		int osize = conn.output().size();

		try {
			conn.done = false;

			protocol.process(conn);

			if (!conn.closed && !conn.isAsync()) {
				conn.done();
			}

			U.debug("Completed message processing");
			return true;

		} catch (IncompleteReadException e) {
			U.debug("Incomplete message");

			// input not complete, so rollback
			conn.input().position(pos);
			conn.input().limit(limit);

			conn.output().deleteAfter(osize);

		} catch (ProtocolException e) {
			U.warn("Protocol error", "error", e);
			conn.output().deleteAfter(osize);
			conn.write(U.or(e.getMessage(), "Protocol error!"));
			conn.error();
			conn.close(true);

		} catch (Throwable e) {
			U.error("Failed to process message!", e);
			conn.close(true);
		}

		return false;
	}

	public void close(RapidoidConnection conn) {
		close(conn.key);
	}

	private void close(SelectionKey key) {
		try {
			RapidoidConnection conn = (RapidoidConnection) key.attachment();

			clearKey(key);

			if (conn != null) {
				if (!conn.closed) {
					U.trace("Closing connection", "connection", conn);
					assert conn.key == key;
					conn.reset();
					connections.release(conn);
				}
			}

		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void clearKey(SelectionKey key) throws IOException {
		if (key.isValid()) {
			SocketChannel socketChannel = (SocketChannel) key.channel();
			socketChannel.close();
			key.attach(null);
			key.cancel();
		}
	}

	@Override
	protected void writeOP(SelectionKey key) throws IOException {
		RapidoidConnection conn = (RapidoidConnection) key.attachment();
		SocketChannel socketChannel = (SocketChannel) key.channel();

		checkOnSameThread();

		try {
			int wrote = conn.output.writeTo(socketChannel);
			conn.output.deleteBefore(wrote);

			boolean complete = conn.output.size() == 0;

			if (conn.closeAfterWrite() && complete) {
				close(conn);
			} else {
				if (complete) {
					key.interestOps(SelectionKey.OP_READ);
				} else {
					key.interestOps(SelectionKey.OP_READ + SelectionKey.OP_WRITE);
				}
				conn.wrote(complete);
			}
		} catch (IOException e) {
			close(conn);
		}
	}

	public void wantToWrite(RapidoidConnection conn) {
		if (onSameThread()) {
			conn.key.interestOps(SelectionKey.OP_WRITE);
		} else {
			wantToWriteAsync(conn);
		}
	}

	private void wantToWriteAsync(RapidoidConnection conn) {
		synchronized (done) {
			done.add(conn);
		}

		selector.wakeup();
	}

	@Override
	protected void doProcessing() {

		long now = U.time();
		int connectingN = connecting.size();

		for (int i = 0; i < connectingN; i++) {
			ConnectionTarget target = connecting.poll();
			assert target != null;

			if (target.retryAfter < now) {
				U.debug("connecting", "address", target.addr);

				try {
					SelectionKey newKey = target.socketChannel.register(selector, SelectionKey.OP_CONNECT);
					newKey.attach(target);
				} catch (ClosedChannelException e) {
					U.warn("Closed channel", e);
				}
			} else {
				connecting.add(target);
			}
		}

		RapidoidChannel channel;

		while ((channel = connected.poll()) != null) {

			SocketChannel socketChannel = channel.socketChannel;
			U.debug("connected", "address", socketChannel.socket().getRemoteSocketAddress());

			try {
				SelectionKey newKey = socketChannel.register(selector, SelectionKey.OP_READ);
				RapidoidConnection conn = attachConn(newKey);
				conn.setClient(channel.isClient);

				try {
					processNext(conn);
				} finally {
					conn.setInitial(false);
				}

			} catch (ClosedChannelException e) {
				U.warn("Closed channel", e);
			}
		}

		RapidoidConnection restartedConn;
		while ((restartedConn = restarting.poll()) != null) {
			U.debug("restarting", "connection", restartedConn);

			processNext(restartedConn);
		}

		synchronized (done) {
			for (int i = 0; i < done.size(); i++) {
				RapidoidConnection conn = done.get(i);
				if (conn.key != null && conn.key.isValid()) {
					conn.key.interestOps(SelectionKey.OP_WRITE);
				}
			}
			done.clear();
		}
	}

	private RapidoidConnection attachConn(SelectionKey key) {
		Object attachment = key.attachment();
		assert attachment == null || attachment instanceof ConnectionTarget;

		RapidoidConnection conn = connections.get();

		U.must(conn.closed);
		conn.closed = false;

		conn.key = key;

		if (isProtocolListener) {
			conn.setListener((CtxListener) protocol);
		}

		key.attach(conn);

		return conn;
	}

	@Override
	protected void failedOP(SelectionKey key, Throwable e) {
		U.error("Network error", e);
		close(key);
	}

	public void restart(RapidoidConnection conn) {
		restarting.add(conn);
	}

	public RapidoidConnection newConnection() {
		return new RapidoidConnection(RapidoidWorker.this, bufs);
	}

}
