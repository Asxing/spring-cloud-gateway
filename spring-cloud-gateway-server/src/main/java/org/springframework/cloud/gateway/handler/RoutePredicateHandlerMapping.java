/*
 * Copyright 2013-2020 the original author or authors.
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

package org.springframework.cloud.gateway.handler;

import java.util.function.Function;
import java.util.logging.Level;

import ch.qos.logback.classic.Logger;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.core.env.Environment;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.reactive.handler.AbstractHandlerMapping;
import org.springframework.web.server.ServerWebExchange;

import static org.springframework.cloud.gateway.handler.RoutePredicateHandlerMapping.ManagementPortType.DIFFERENT;
import static org.springframework.cloud.gateway.handler.RoutePredicateHandlerMapping.ManagementPortType.DISABLED;
import static org.springframework.cloud.gateway.handler.RoutePredicateHandlerMapping.ManagementPortType.SAME;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_HANDLER_MAPPER_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_PREDICATE_ROUTE_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * @author Spencer Gibb
 */
public class RoutePredicateHandlerMapping extends AbstractHandlerMapping {

	protected static final Log logger = LogFactory.getLog(RoutePredicateHandlerMapping.class);

	private final FilteringWebHandler webHandler;

	private final RouteLocator routeLocator;

	private final Integer managementPort;

	private final ManagementPortType managementPortType;

	public RoutePredicateHandlerMapping(FilteringWebHandler webHandler,
			RouteLocator routeLocator, GlobalCorsProperties globalCorsProperties,
			Environment environment) {
		this.webHandler = webHandler;
		this.routeLocator = routeLocator;

		this.managementPort = getPortProperty(environment, "management.server.");
		this.managementPortType = getManagementPortType(environment);
		setOrder(1);
		setCorsConfigurations(globalCorsProperties.getCorsConfigurations());
	}

	// 设置管理端口是否为 8080 或者是否和服务端口一直，如果一直设置 SAME，否则设置为 Different.
	private ManagementPortType getManagementPortType(Environment environment) {
		Integer serverPort = getPortProperty(environment, "server.");
		if (this.managementPort != null && this.managementPort < 0) {
			logger.info("管理端口小于零，设置端口类型为 disabled -> RoutePredicateHandlerMapping#getManagementPortType");
			return DISABLED;
		}
		return ((this.managementPort == null
				|| (serverPort == null && this.managementPort.equals(8080))
				|| (this.managementPort != 0 && this.managementPort.equals(serverPort)))
				? SAME : DIFFERENT);
	}

	private static Integer getPortProperty(Environment environment, String prefix) {
		logger.info("获取管理端口 -> RoutePredicateHandlerMapping#getPortProperty");
		return environment.getProperty(prefix + "port", Integer.class);
	}

	@Override
	protected Mono<?> getHandlerInternal(ServerWebExchange exchange) {
		logger.info(" -> RoutePredicateHandlerMapping#getHandlerInternal");
		// don't handle requests on management port if set and different than server port
		if (this.managementPortType == DIFFERENT && this.managementPort != null
				&& exchange.getRequest().getURI().getPort() == this.managementPort) {

			return Mono.empty();
		}
		logger.info("设置当前处理使用的 GATEWAY_HANDLER_MAPPER_ATTR 是 RoutePredicateHandlerMapping "
				+ "-> RoutePredicateHandlerMapping#getHandlerInternal");
		exchange.getAttributes().put(GATEWAY_HANDLER_MAPPER_ATTR, getSimpleName());
		Mono<Route> routeMono = lookupRoute(exchange);
		logger.info("当前匹配的 Route 是：" + routeMono + " -> RoutePredicateHandlerMapping#getHandlerInternal");
		return routeMono
				.log("route-predicate-handler-mapping", Level.FINER) //name this
				.flatMap((Function<Route, Mono<?>>) r -> {
					logger.info("移除当前缓存的 GATEWAY_PREDICATE_ROUTE_ATTR : " + exchange.getAttributes()
							.get(GATEWAY_PREDICATE_ROUTE_ATTR) + " -> RoutePredicateHandlerMapping#getHandlerInternal");
					exchange.getAttributes().remove(GATEWAY_PREDICATE_ROUTE_ATTR);
					if (logger.isDebugEnabled()) {
						logger.debug(
								"Mapping [" + getExchangeDesc(exchange) + "] to " + r);
					}
					logger.info("设置当前正在使用的 GATEWAY_ROUTE_ATTR 为：" + r +
							" -> RoutePredicateHandlerMapping#getHandlerInternal");
					exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, r);
					return Mono.just(webHandler);
				}).switchIfEmpty(Mono.empty().then(Mono.fromRunnable(() -> {
					logger.info("未找到合适的 Route，清理历史 Route 上下文，返回空 -> "
							+ "RoutePredicateHandlerMapping#getHandlerInternal");
					exchange.getAttributes().remove(GATEWAY_PREDICATE_ROUTE_ATTR);
					if (logger.isTraceEnabled()) {
						logger.trace("No RouteDefinition found for ["
								+ getExchangeDesc(exchange) + "]");
					}
				})));
	}

	@Override
	protected CorsConfiguration getCorsConfiguration(Object handler,
			ServerWebExchange exchange) {
		logger.info("获取 CorsConfiguration 配置");
		// TODO: support cors configuration via properties on a route see gh-229
		// see RequestMappingHandlerMapping.initCorsConfiguration()
		// also see
		// https://github.com/spring-projects/spring-framework/blob/master/spring-web/src/test/java/org/springframework/web/cors/reactive/CorsWebFilterTests.java
		return super.getCorsConfiguration(handler, exchange);
	}

	// TODO: get desc from factory?
	private String getExchangeDesc(ServerWebExchange exchange) {
		StringBuilder out = new StringBuilder();
		out.append("Exchange: ");
		out.append(exchange.getRequest().getMethod());
		out.append(" ");
		out.append(exchange.getRequest().getURI());
		return out.toString();
	}

	protected Mono<Route> lookupRoute(ServerWebExchange exchange) {
		logger.info("lookupRoute 开始匹配 Route 贼关键 -> RoutePredicateHandlerMapping#lookupRoute");
		return this.routeLocator.getRoutes()
				// individually filter routes so that filterWhen error delaying is not a problem
				// 单独处理，以便在出现错误的时候，返回空
				.concatMap(route ->
						Mono.just(route)
								.filterWhen(r -> {
									// add the current route we are testing
									logger.info("设置当前执行的 routeId GATEWAY_PREDICATE_ROUTE_ATTR: " +
											r.getId() + " -> RoutePredicateHandlerMapping#lookupRoute");
									exchange.getAttributes().put(GATEWAY_PREDICATE_ROUTE_ATTR, r.getId());
									return r.getPredicate().apply(exchange);
								})
								// instead of immediately stopping main flux due to error, log and
								// swallow it
								.doOnError(e -> logger.error(
										"Error applying predicate for route: " + route.getId(),
										e))
								.onErrorResume(e -> Mono.empty()))
				// .defaultIfEmpty() put a static Route not found
				// or .switchIfEmpty()
				// .switchIfEmpty(Mono.<Route>empty().log("noroute"))
				.next()
				// TODO: error handling
				.map(route -> {
					if (logger.isDebugEnabled()) {
						logger.debug("Route matched: " + route.getId());
					}
					logger.info("校验 Route 的有效性 -> RoutePredicateHandlerMapping#lookupRoute");
					validateRoute(route, exchange);
					return route;
				});

		/*
		 * TODO: trace logging if (logger.isTraceEnabled()) {
		 * logger.trace("RouteDefinition did not match: " + routeDefinition.getId()); }
		 */
	}

	/**
	 * Validate the given handler against the current request.
	 * <p>
	 * The default implementation is empty. Can be overridden in subclasses, for example
	 * to enforce specific preconditions expressed in URL mappings.
	 * @param route the Route object to validate
	 * @param exchange current exchange
	 * @throws Exception if validation failed
	 */
	@SuppressWarnings("UnusedParameters")
	protected void validateRoute(Route route, ServerWebExchange exchange) {
	}

	protected String getSimpleName() {
		return "RoutePredicateHandlerMapping";
	}

	public enum ManagementPortType {

		/**
		 * The management port has been disabled.
		 */
		DISABLED,

		/**
		 * The management port is the same as the server port.
		 */
		SAME,

		/**
		 * The management port and server port are different.
		 */
		DIFFERENT;

	}

}
