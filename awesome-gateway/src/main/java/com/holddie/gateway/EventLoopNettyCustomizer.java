package com.holddie.gateway;

import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import reactor.netty.http.server.HttpServer;

import org.springframework.boot.web.embedded.netty.NettyServerCustomizer;

public class EventLoopNettyCustomizer implements NettyServerCustomizer {

	@Override
	public HttpServer apply(HttpServer httpServer) {
		EventLoopGroup bossGroup = new NioEventLoopGroup(1);
		EventLoopGroup workGroup = new NioEventLoopGroup(10);
		return httpServer.tcpConfiguration(tcpServer -> tcpServer
				.bootstrap(serverBootstrap -> serverBootstrap
						.group(bossGroup, workGroup)
						.handler(new LoggingHandler(LogLevel.DEBUG))
						.option(ChannelOption.SO_BACKLOG, 128)
						.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
						.channel(NioServerSocketChannel.class))
				.doOnConnection(connection ->
						connection.addHandlerLast(new WriteTimeoutHandler(5)))
		);
	}
}