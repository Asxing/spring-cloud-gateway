package com.holddie.gateway;

import reactor.core.publisher.Mono;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
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
		return builder.routes().
				route("path_route", r -> r
						.path("/get")
						.filters(f -> f.addRequestHeader("Hello", "World"))
						.uri("http://httpbin.org")).
				route("hystrix_route", r -> r
						.host("*.hystrix.com")
						.filters(f ->
								f.hystrix(config -> config
										.setName("mycmd")
										.setFallbackUri("forward:/fallback")
								)
						)
						.uri("http://httpbin.org:80"))
				.build();
	}

	@RequestMapping("/fallback")
	public Mono<String> fallback() {
		return Mono.just("fallback");
	}
}