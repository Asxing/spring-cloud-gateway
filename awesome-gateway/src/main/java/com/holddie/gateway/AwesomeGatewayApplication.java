/*
 * Copyright 2013-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.holddie.gateway;

import reactor.core.publisher.Mono;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@SpringBootApplication
public class AwesomeGatewayApplication {

	public static void main(String[] args) {
		SpringApplication.run(AwesomeGatewayApplication.class, args);
	}

	@Bean
	public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
		return builder.routes().route("flux",
				r -> r.path("/flux").filters(f -> f.addRequestHeader("Hello", "World"))
						.uri("http://localhost:8081/flux"))
				.route("mvc",
						r -> r.path("/mvc").filters(f -> f.addRequestHeader("Hello", "World"))
								.uri("http://localhost:8082/mvc"))
				.route("hystrix_route",
						r -> r.host("*.hystrix.com")
								.filters(f -> f.hystrix(config -> config.setName("mycmd")
										.setFallbackUri("forward:/fallback")))
								.uri("http://httpbin.org:80").order(1))
				.build();
	}

	@RequestMapping("/fallback")
	public Mono<String> fallback() {
		return Mono.just("fallback");
	}

	@Bean
	public NettyReactiveWebServerFactory nettyReactiveWebServerFactory() {
		NettyReactiveWebServerFactory webServerFactory = new NettyReactiveWebServerFactory();
		webServerFactory.addServerCustomizers(new EventLoopNettyCustomizer());
		return webServerFactory;
	}

	@Component
	public class NettyWebServerFactoryPortCustomizer
			implements WebServerFactoryCustomizer<NettyReactiveWebServerFactory> {
		@Override
		public void customize(NettyReactiveWebServerFactory serverFactory) {
			serverFactory.setPort(8089);
		}
	}
}
