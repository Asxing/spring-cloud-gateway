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

package org.springframework.cloud.gateway.config;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;

import com.netflix.hystrix.HystrixObservableCommand;
import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.tcp.ProxyProvider;
import rx.RxReactiveStreams;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.NoneNestedConditions;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.autoconfigure.web.embedded.NettyWebServerFactoryCustomizer;
import org.springframework.boot.autoconfigure.web.reactive.HttpHandlerAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.WebFluxAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.PropertyMapper;
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.cloud.gateway.actuate.GatewayControllerEndpoint;
import org.springframework.cloud.gateway.actuate.GatewayLegacyControllerEndpoint;
import org.springframework.cloud.gateway.config.conditional.ConditionalOnEnabledFilter;
import org.springframework.cloud.gateway.config.conditional.ConditionalOnEnabledGlobalFilter;
import org.springframework.cloud.gateway.config.conditional.ConditionalOnEnabledPredicate;
import org.springframework.cloud.gateway.filter.AdaptCachedBodyGlobalFilter;
import org.springframework.cloud.gateway.filter.ForwardPathFilter;
import org.springframework.cloud.gateway.filter.ForwardRoutingFilter;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.NettyRoutingFilter;
import org.springframework.cloud.gateway.filter.NettyWriteResponseFilter;
import org.springframework.cloud.gateway.filter.RemoveCachedBodyFilter;
import org.springframework.cloud.gateway.filter.RouteToRequestUrlFilter;
import org.springframework.cloud.gateway.filter.WebsocketRoutingFilter;
import org.springframework.cloud.gateway.filter.WeightCalculatorWebFilter;
import org.springframework.cloud.gateway.filter.factory.AddRequestHeaderGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.AddRequestParameterGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.AddResponseHeaderGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.DedupeResponseHeaderGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.FallbackHeadersGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.GatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.HystrixGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.MapRequestHeaderGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.PrefixPathGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.PreserveHostHeaderGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.RedirectToGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.RemoveRequestHeaderGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.RemoveRequestParameterGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.RemoveResponseHeaderGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.RequestHeaderSizeGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.RequestHeaderToRequestUriGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.RequestRateLimiterGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.RequestSizeGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.RetryGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.RewriteLocationResponseHeaderGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.RewritePathGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.RewriteResponseHeaderGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.SaveSessionGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.SecureHeadersGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.SecureHeadersProperties;
import org.springframework.cloud.gateway.filter.factory.SetPathGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.SetRequestHeaderGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.SetRequestHostHeaderGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.SetResponseHeaderGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.SetStatusGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.StripPrefixGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.rewrite.GzipMessageBodyResolver;
import org.springframework.cloud.gateway.filter.factory.rewrite.MessageBodyDecoder;
import org.springframework.cloud.gateway.filter.factory.rewrite.MessageBodyEncoder;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyRequestBodyGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyResponseBodyGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.headers.ForwardedHeadersFilter;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.cloud.gateway.filter.headers.RemoveHopByHopHeadersFilter;
import org.springframework.cloud.gateway.filter.headers.XForwardedHeadersFilter;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.PrincipalNameKeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.handler.FilteringWebHandler;
import org.springframework.cloud.gateway.handler.RoutePredicateHandlerMapping;
import org.springframework.cloud.gateway.handler.predicate.AfterRoutePredicateFactory;
import org.springframework.cloud.gateway.handler.predicate.BeforeRoutePredicateFactory;
import org.springframework.cloud.gateway.handler.predicate.BetweenRoutePredicateFactory;
import org.springframework.cloud.gateway.handler.predicate.CloudFoundryRouteServiceRoutePredicateFactory;
import org.springframework.cloud.gateway.handler.predicate.CookieRoutePredicateFactory;
import org.springframework.cloud.gateway.handler.predicate.HeaderRoutePredicateFactory;
import org.springframework.cloud.gateway.handler.predicate.HostRoutePredicateFactory;
import org.springframework.cloud.gateway.handler.predicate.MethodRoutePredicateFactory;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.cloud.gateway.handler.predicate.QueryRoutePredicateFactory;
import org.springframework.cloud.gateway.handler.predicate.ReadBodyRoutePredicateFactory;
import org.springframework.cloud.gateway.handler.predicate.RemoteAddrRoutePredicateFactory;
import org.springframework.cloud.gateway.handler.predicate.RoutePredicateFactory;
import org.springframework.cloud.gateway.handler.predicate.WeightRoutePredicateFactory;
import org.springframework.cloud.gateway.route.CachingRouteLocator;
import org.springframework.cloud.gateway.route.CompositeRouteDefinitionLocator;
import org.springframework.cloud.gateway.route.CompositeRouteLocator;
import org.springframework.cloud.gateway.route.InMemoryRouteDefinitionRepository;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.cloud.gateway.route.RouteDefinitionRepository;
import org.springframework.cloud.gateway.route.RouteDefinitionRouteLocator;
import org.springframework.cloud.gateway.route.RouteDefinitionWriter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.RouteRefreshListener;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.cloud.gateway.support.StringToZonedDateTimeConverter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.env.Environment;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.validation.Validator;
import org.springframework.web.reactive.DispatcherHandler;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import org.springframework.web.reactive.socket.server.RequestUpgradeStrategy;
import org.springframework.web.reactive.socket.server.WebSocketService;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy;

import static org.springframework.cloud.gateway.config.HttpClientProperties.Pool.PoolType.DISABLED;
import static org.springframework.cloud.gateway.config.HttpClientProperties.Pool.PoolType.FIXED;

/**
 * @author Spencer Gibb
 * @author Ziemowit Stolarczyk
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.cloud.gateway.enabled", matchIfMissing = true)
@EnableConfigurationProperties
@AutoConfigureBefore({HttpHandlerAutoConfiguration.class,
		WebFluxAutoConfiguration.class})
@AutoConfigureAfter({GatewayLoadBalancerClientAutoConfiguration.class,
		GatewayClassPathWarningAutoConfiguration.class})
@ConditionalOnClass(DispatcherHandler.class)
public class GatewayAutoConfiguration {

	protected final Log logger = LogFactory.getLog(getClass());

	@Bean
	public StringToZonedDateTimeConverter stringToZonedDateTimeConverter() {
		return new StringToZonedDateTimeConverter();
	}

	@Bean
	public RouteLocatorBuilder routeLocatorBuilder(
			ConfigurableApplicationContext context) {
		return new RouteLocatorBuilder(context);
	}

	@Bean
	@ConditionalOnMissingBean
	public PropertiesRouteDefinitionLocator propertiesRouteDefinitionLocator(
			GatewayProperties properties) {
		return new PropertiesRouteDefinitionLocator(properties);
	}

	@Bean
	@ConditionalOnMissingBean(RouteDefinitionRepository.class)
	public InMemoryRouteDefinitionRepository inMemoryRouteDefinitionRepository() {
		return new InMemoryRouteDefinitionRepository();
	}

	@Bean
	@Primary
	public RouteDefinitionLocator routeDefinitionLocator(
			List<RouteDefinitionLocator> routeDefinitionLocators) {
		logger.info("收集所有路由表 -> GatewayAutoConfiguration#routeDefinitionLocator");
		return new CompositeRouteDefinitionLocator(
				Flux.fromIterable(routeDefinitionLocators));
	}

	@Bean
	public ConfigurationService gatewayConfigurationService(BeanFactory beanFactory,
			@Qualifier("webFluxConversionService") ObjectProvider<ConversionService> conversionService,
			ObjectProvider<Validator> validator) {
		return new ConfigurationService(beanFactory, conversionService, validator);
	}

	@Bean
	public RouteLocator routeDefinitionRouteLocator(GatewayProperties properties,
			List<GatewayFilterFactory> gatewayFilters,
			List<RoutePredicateFactory> predicates,
			RouteDefinitionLocator routeDefinitionLocator,
			ConfigurationService configurationService) {
		logger.info("routeDefinitionRouteLocator -> GatewayAutoConfiguration#routeDefinitionRouteLocator");
		return new RouteDefinitionRouteLocator(routeDefinitionLocator, predicates,
				gatewayFilters, properties, configurationService);
	}

	@Bean
	@Primary
	@ConditionalOnMissingBean(name = "cachedCompositeRouteLocator")
	// TODO: property to disable composite?
	public RouteLocator cachedCompositeRouteLocator(List<RouteLocator> routeLocators) {
		logger.info("init routeLocator -> GatewayAutoConfiguration#cachedCompositeRouteLocator");
		return new CachingRouteLocator(
				new CompositeRouteLocator(Flux.fromIterable(routeLocators)));
	}

	@Bean
	@ConditionalOnClass(
			name = "org.springframework.cloud.client.discovery.event.HeartbeatMonitor")
	public RouteRefreshListener routeRefreshListener(
			ApplicationEventPublisher publisher) {
		return new RouteRefreshListener(publisher);
	}

	@Bean
	public FilteringWebHandler filteringWebHandler(List<GlobalFilter> globalFilters) {
		logger.info("init FilteringWebHandler -> GatewayAutoConfiguration#filteringWebHandler");
		logger.info("--------web filter--------");
		for (GlobalFilter x : globalFilters) {
			String[] split = x.getClass()
					.toString()
					.split("\\.");
			logger.info(split[split.length - 1]);
		}
		logger.info("--------end--------");
		return new FilteringWebHandler(globalFilters);
	}

	@Bean
	public GlobalCorsProperties globalCorsProperties() {
		logger.info("init GlobalCorsProperties -> GatewayAutoConfiguration#globalCorsProperties");
		return new GlobalCorsProperties();
	}

	// 最关键地方，打头阵，直接开始组装了.
	@Bean
	public RoutePredicateHandlerMapping routePredicateHandlerMapping(
			FilteringWebHandler webHandler, RouteLocator routeLocator,
			GlobalCorsProperties globalCorsProperties, Environment environment) {
		logger.info("直接开始组装了 routePredicateHandlerMapping -> GatewayAutoConfiguration#routePredicateHandlerMapping");
		return new RoutePredicateHandlerMapping(webHandler, routeLocator,
				globalCorsProperties, environment);
	}

	@Bean
	public GatewayProperties gatewayProperties() {
		return new GatewayProperties();
	}

	// ConfigurationProperty beans

	@Bean
	public SecureHeadersProperties secureHeadersProperties() {
		return new SecureHeadersProperties();
	}

	@Bean
	@ConditionalOnProperty(name = "spring.cloud.gateway.forwarded.enabled",
			matchIfMissing = true)
	public ForwardedHeadersFilter forwardedHeadersFilter() {
		return new ForwardedHeadersFilter();
	}

	// HttpHeaderFilter beans

	@Bean
	public RemoveHopByHopHeadersFilter removeHopByHopHeadersFilter() {
		return new RemoveHopByHopHeadersFilter();
	}

	@Bean
	@ConditionalOnProperty(name = "spring.cloud.gateway.x-forwarded.enabled",
			matchIfMissing = true)
	public XForwardedHeadersFilter xForwardedHeadersFilter() {
		return new XForwardedHeadersFilter();
	}

	// GlobalFilter beans

	@Bean
	@ConditionalOnEnabledGlobalFilter
	public AdaptCachedBodyGlobalFilter adaptCachedBodyGlobalFilter() {
		logger.info("ConditionalOnEnabledGlobalFilter !! GatewayAutoConfiguration:302 !! methodName: adaptCachedBodyGlobalFilter ");
		return new AdaptCachedBodyGlobalFilter();
	}

	@Bean
	@ConditionalOnEnabledGlobalFilter
	public RemoveCachedBodyFilter removeCachedBodyFilter() {
		logger.info("ConditionalOnEnabledGlobalFilter -> GatewayAutoConfiguration#removeCachedBodyFilter");
		return new RemoveCachedBodyFilter();
	}

	@Bean
	@ConditionalOnEnabledGlobalFilter
	public RouteToRequestUrlFilter routeToRequestUrlFilter() {
		logger.info("ConditionalOnEnabledGlobalFilter -> GatewayAutoConfiguration#routeToRequestUrlFilter");
		return new RouteToRequestUrlFilter();
	}

	@Bean
	@ConditionalOnEnabledGlobalFilter
	public ForwardRoutingFilter forwardRoutingFilter(
			ObjectProvider<DispatcherHandler> dispatcherHandler) {
		logger.info("使用到了 dispatcherHandler -> GatewayAutoConfiguration#forwardRoutingFilter");
		return new ForwardRoutingFilter(dispatcherHandler);
	}

	@Bean
	@ConditionalOnEnabledGlobalFilter
	public ForwardPathFilter forwardPathFilter() {
		return new ForwardPathFilter();
	}

	@Bean
	public WebSocketService webSocketService(
			RequestUpgradeStrategy requestUpgradeStrategy) {
		return new HandshakeWebSocketService(requestUpgradeStrategy);
	}

	@Bean
	@ConditionalOnEnabledGlobalFilter
	public WebsocketRoutingFilter websocketRoutingFilter(WebSocketClient webSocketClient,
			WebSocketService webSocketService,
			ObjectProvider<List<HttpHeadersFilter>> headersFilters) {
		logger.info("ConditionalOnEnabledGlobalFilter !! GatewayAutoConfiguration:347 -> methodName: websocketRoutingFilter ");
		return new WebsocketRoutingFilter(webSocketClient, webSocketService,
				headersFilters);
	}

	@Bean
	public WeightCalculatorWebFilter weightCalculatorWebFilter(
			ConfigurationService configurationService,
			ObjectProvider<RouteLocator> routeLocator) {
		return new WeightCalculatorWebFilter(routeLocator, configurationService);
	}

	/*
	 * @Bean //TODO: default over netty? configurable public WebClientHttpRoutingFilter
	 * webClientHttpRoutingFilter() { //TODO: WebClient bean return new
	 * WebClientHttpRoutingFilter(WebClient.routes().build()); }
	 *
	 * @Bean public WebClientWriteResponseFilter webClientWriteResponseFilter() { return
	 * new WebClientWriteResponseFilter(); }
	 */

	// Predicate Factory beans

	@Bean
	@ConditionalOnEnabledPredicate
	public AfterRoutePredicateFactory afterRoutePredicateFactory() {
		logger.info("ConditionalOnEnabledPredicate -> GatewayAutoConfiguration#afterRoutePredicateFactory");
		return new AfterRoutePredicateFactory();
	}

	@Bean
	@ConditionalOnEnabledPredicate
	public BeforeRoutePredicateFactory beforeRoutePredicateFactory() {
		logger.info("ConditionalOnEnabledPredicate -> GatewayAutoConfiguration#beforeRoutePredicateFactory");
		return new BeforeRoutePredicateFactory();
	}

	@Bean
	@ConditionalOnEnabledPredicate
	public BetweenRoutePredicateFactory betweenRoutePredicateFactory() {
		logger.info("ConditionalOnEnabledPredicate -> GatewayAutoConfiguration#betweenRoutePredicateFactory");
		return new BetweenRoutePredicateFactory();
	}

	@Bean
	@ConditionalOnEnabledPredicate
	public CookieRoutePredicateFactory cookieRoutePredicateFactory() {
		logger.info("ConditionalOnEnabledPredicate -> GatewayAutoConfiguration#cookieRoutePredicateFactory");
		return new CookieRoutePredicateFactory();
	}

	@Bean
	@ConditionalOnEnabledPredicate
	public HeaderRoutePredicateFactory headerRoutePredicateFactory() {
		logger.info("ConditionalOnEnabledPredicate -> GatewayAutoConfiguration#headerRoutePredicateFactory");
		return new HeaderRoutePredicateFactory();
	}

	@Bean
	@ConditionalOnEnabledPredicate
	public HostRoutePredicateFactory hostRoutePredicateFactory() {
		logger.info("ConditionalOnEnabledPredicate -> GatewayAutoConfiguration#hostRoutePredicateFactory");
		return new HostRoutePredicateFactory();
	}

	@Bean
	@ConditionalOnEnabledPredicate
	public MethodRoutePredicateFactory methodRoutePredicateFactory() {
		logger.info("ConditionalOnEnabledPredicate -> GatewayAutoConfiguration#methodRoutePredicateFactory");
		return new MethodRoutePredicateFactory();
	}

	@Bean
	@ConditionalOnEnabledPredicate
	public PathRoutePredicateFactory pathRoutePredicateFactory() {
		logger.info("ConditionalOnEnabledPredicate -> GatewayAutoConfiguration#pathRoutePredicateFactory");
		return new PathRoutePredicateFactory();
	}

	@Bean
	@ConditionalOnEnabledPredicate
	public QueryRoutePredicateFactory queryRoutePredicateFactory() {
		logger.info("ConditionalOnEnabledPredicate -> GatewayAutoConfiguration#queryRoutePredicateFactory");
		return new QueryRoutePredicateFactory();
	}

	@Bean
	@ConditionalOnEnabledPredicate
	public ReadBodyRoutePredicateFactory readBodyRoutePredicateFactory(
			ServerCodecConfigurer codecConfigurer) {
		logger.info("ConditionalOnEnabledPredicate -> GatewayAutoConfiguration#readBodyRoutePredicateFactory");
		return new ReadBodyRoutePredicateFactory(codecConfigurer.getReaders());
	}

	@Bean
	@ConditionalOnEnabledPredicate
	public RemoteAddrRoutePredicateFactory remoteAddrRoutePredicateFactory() {
		logger.info("ConditionalOnEnabledPredicate -> GatewayAutoConfiguration#remoteAddrRoutePredicateFactory");
		return new RemoteAddrRoutePredicateFactory();
	}

	@Bean
	@DependsOn("weightCalculatorWebFilter")
	@ConditionalOnEnabledPredicate
	public WeightRoutePredicateFactory weightRoutePredicateFactory() {
		logger.info("ConditionalOnEnabledPredicate -> GatewayAutoConfiguration#weightRoutePredicateFactory");
		return new WeightRoutePredicateFactory();
	}

	@Bean
	@ConditionalOnEnabledPredicate
	public CloudFoundryRouteServiceRoutePredicateFactory cloudFoundryRouteServiceRoutePredicateFactory() {
		logger.info("ConditionalOnEnabledPredicate -> GatewayAutoConfiguration#cloudFoundryRouteServiceRoutePredicateFactory");
		return new CloudFoundryRouteServiceRoutePredicateFactory();
	}

	// GatewayFilter Factory beans

	@Bean
	@ConditionalOnEnabledFilter
	public AddRequestHeaderGatewayFilterFactory addRequestHeaderGatewayFilterFactory() {
		logger.info("ConditionalOnEnabledFilter -> GatewayAutoConfiguration#addRequestHeaderGatewayFilterFactory");
		return new AddRequestHeaderGatewayFilterFactory();
	}

	@Bean
	@ConditionalOnEnabledFilter
	public MapRequestHeaderGatewayFilterFactory mapRequestHeaderGatewayFilterFactory() {
		logger.info("ConditionalOnEnabledFilter -> GatewayAutoConfiguration#mapRequestHeaderGatewayFilterFactory");
		return new MapRequestHeaderGatewayFilterFactory();
	}

	@Bean
	@ConditionalOnEnabledFilter
	public AddRequestParameterGatewayFilterFactory addRequestParameterGatewayFilterFactory() {
		logger.info("ConditionalOnEnabledFilter -> GatewayAutoConfiguration#addRequestParameterGatewayFilterFactory");
		return new AddRequestParameterGatewayFilterFactory();
	}

	@Bean
	@ConditionalOnEnabledFilter
	public AddResponseHeaderGatewayFilterFactory addResponseHeaderGatewayFilterFactory() {
		logger.info("ConditionalOnEnabledFilter -> GatewayAutoConfiguration#addResponseHeaderGatewayFilterFactory");
		return new AddResponseHeaderGatewayFilterFactory();
	}

	@Bean
	@ConditionalOnEnabledFilter
	public ModifyRequestBodyGatewayFilterFactory modifyRequestBodyGatewayFilterFactory(
			ServerCodecConfigurer codecConfigurer) {
		logger.info("ConditionalOnEnabledFilter -> GatewayAutoConfiguration#modifyRequestBodyGatewayFilterFactory");
		return new ModifyRequestBodyGatewayFilterFactory(codecConfigurer.getReaders());
	}

	@Bean
	@ConditionalOnEnabledFilter
	public DedupeResponseHeaderGatewayFilterFactory dedupeResponseHeaderGatewayFilterFactory() {
		logger.info("ConditionalOnEnabledFilter -> GatewayAutoConfiguration#dedupeResponseHeaderGatewayFilterFactory");
		return new DedupeResponseHeaderGatewayFilterFactory();
	}

	@Bean
	@ConditionalOnEnabledFilter
	public ModifyResponseBodyGatewayFilterFactory modifyResponseBodyGatewayFilterFactory(
			ServerCodecConfigurer codecConfigurer, Set<MessageBodyDecoder> bodyDecoders,
			Set<MessageBodyEncoder> bodyEncoders) {
		logger.info("ConditionalOnEnabledFilter -> GatewayAutoConfiguration#modifyResponseBodyGatewayFilterFactory");
		return new ModifyResponseBodyGatewayFilterFactory(codecConfigurer.getReaders(),
				bodyDecoders, bodyEncoders);
	}

	@Bean
	@ConditionalOnEnabledFilter
	public PrefixPathGatewayFilterFactory prefixPathGatewayFilterFactory() {
		logger.info("ConditionalOnEnabledFilter -> GatewayAutoConfiguration#prefixPathGatewayFilterFactory");
		return new PrefixPathGatewayFilterFactory();
	}

	@Bean
	@ConditionalOnEnabledFilter
	public PreserveHostHeaderGatewayFilterFactory preserveHostHeaderGatewayFilterFactory() {
		logger.info("ConditionalOnEnabledFilter -> GatewayAutoConfiguration#preserveHostHeaderGatewayFilterFactory");
		return new PreserveHostHeaderGatewayFilterFactory();
	}

	@Bean
	@ConditionalOnEnabledFilter
	public RedirectToGatewayFilterFactory redirectToGatewayFilterFactory() {
		logger.info("ConditionalOnEnabledFilter -> GatewayAutoConfiguration#redirectToGatewayFilterFactory");
		return new RedirectToGatewayFilterFactory();
	}

	@Bean
	@ConditionalOnEnabledFilter
	public RemoveRequestHeaderGatewayFilterFactory removeRequestHeaderGatewayFilterFactory() {
		logger.info("ConditionalOnEnabledFilter -> GatewayAutoConfiguration#removeRequestHeaderGatewayFilterFactory");
		return new RemoveRequestHeaderGatewayFilterFactory();
	}

	@Bean
	@ConditionalOnEnabledFilter
	public RemoveRequestParameterGatewayFilterFactory removeRequestParameterGatewayFilterFactory() {
		logger.info("ConditionalOnEnabledFilter -> GatewayAutoConfiguration#removeRequestParameterGatewayFilterFactory");
		return new RemoveRequestParameterGatewayFilterFactory();
	}

	@Bean
	@ConditionalOnEnabledFilter
	public RemoveResponseHeaderGatewayFilterFactory removeResponseHeaderGatewayFilterFactory() {
		logger.info("ConditionalOnEnabledFilter -> GatewayAutoConfiguration#removeResponseHeaderGatewayFilterFactory");
		return new RemoveResponseHeaderGatewayFilterFactory();
	}

	@Bean(name = PrincipalNameKeyResolver.BEAN_NAME)
	@ConditionalOnBean(RateLimiter.class)
	@ConditionalOnMissingBean(KeyResolver.class)
	public PrincipalNameKeyResolver principalNameKeyResolver() {
		return new PrincipalNameKeyResolver();
	}

	@Bean
	@ConditionalOnBean({ RateLimiter.class, KeyResolver.class })
	@ConditionalOnEnabledFilter
	public RequestRateLimiterGatewayFilterFactory requestRateLimiterGatewayFilterFactory(
			RateLimiter rateLimiter, KeyResolver resolver) {
		logger.info("ConditionalOnEnabledFilter -> GatewayAutoConfiguration#requestRateLimiterGatewayFilterFactory");
		return new RequestRateLimiterGatewayFilterFactory(rateLimiter, resolver);
	}

	@Bean
	@ConditionalOnEnabledFilter
	public RewritePathGatewayFilterFactory rewritePathGatewayFilterFactory() {
		logger.info("ConditionalOnEnabledFilter -> GatewayAutoConfiguration#rewritePathGatewayFilterFactory");
		return new RewritePathGatewayFilterFactory();
	}

	@Bean
	@ConditionalOnEnabledFilter
	public RetryGatewayFilterFactory retryGatewayFilterFactory() {
		logger.info("ConditionalOnEnabledFilter -> GatewayAutoConfiguration#retryGatewayFilterFactory");
		return new RetryGatewayFilterFactory();
	}

	@Bean
	@ConditionalOnEnabledFilter
	public SetPathGatewayFilterFactory setPathGatewayFilterFactory() {
		logger.info("ConditionalOnEnabledFilter -> GatewayAutoConfiguration#setPathGatewayFilterFactory");
		return new SetPathGatewayFilterFactory();
	}

	@Bean
	@ConditionalOnEnabledFilter
	public SecureHeadersGatewayFilterFactory secureHeadersGatewayFilterFactory(
			SecureHeadersProperties properties) {
		logger.info("ConditionalOnEnabledFilter -> GatewayAutoConfiguration#secureHeadersGatewayFilterFactory");
		return new SecureHeadersGatewayFilterFactory(properties);
	}

	@Bean
	@ConditionalOnEnabledFilter
	public SetRequestHeaderGatewayFilterFactory setRequestHeaderGatewayFilterFactory() {
		logger.info("ConditionalOnEnabledFilter -> GatewayAutoConfiguration#setRequestHeaderGatewayFilterFactory");
		return new SetRequestHeaderGatewayFilterFactory();
	}

	@Bean
	@ConditionalOnEnabledFilter
	public SetRequestHostHeaderGatewayFilterFactory setRequestHostHeaderGatewayFilterFactory() {
		logger.info("ConditionalOnEnabledFilter -> GatewayAutoConfiguration#setRequestHostHeaderGatewayFilterFactory");
		return new SetRequestHostHeaderGatewayFilterFactory();
	}

	@Bean
	@ConditionalOnEnabledFilter
	public SetResponseHeaderGatewayFilterFactory setResponseHeaderGatewayFilterFactory() {
		logger.info("ConditionalOnEnabledFilter -> GatewayAutoConfiguration#setResponseHeaderGatewayFilterFactory");
		return new SetResponseHeaderGatewayFilterFactory();
	}

	@Bean
	@ConditionalOnEnabledFilter
	public RewriteResponseHeaderGatewayFilterFactory rewriteResponseHeaderGatewayFilterFactory() {
		logger.info("ConditionalOnEnabledFilter -> GatewayAutoConfiguration#rewriteResponseHeaderGatewayFilterFactory");
		return new RewriteResponseHeaderGatewayFilterFactory();
	}

	@Bean
	@ConditionalOnEnabledFilter
	public RewriteLocationResponseHeaderGatewayFilterFactory rewriteLocationResponseHeaderGatewayFilterFactory() {
		logger.info("ConditionalOnEnabledFilter -> GatewayAutoConfiguration#rewriteLocationResponseHeaderGatewayFilterFactory");
		return new RewriteLocationResponseHeaderGatewayFilterFactory();
	}

	@Bean
	@ConditionalOnEnabledFilter
	public SetStatusGatewayFilterFactory setStatusGatewayFilterFactory() {
		logger.info("ConditionalOnEnabledFilter -> GatewayAutoConfiguration#setStatusGatewayFilterFactory");
		return new SetStatusGatewayFilterFactory();
	}

	@Bean
	@ConditionalOnEnabledFilter
	public SaveSessionGatewayFilterFactory saveSessionGatewayFilterFactory() {
		logger.info("ConditionalOnEnabledFilter -> GatewayAutoConfiguration#saveSessionGatewayFilterFactory");
		return new SaveSessionGatewayFilterFactory();
	}

	@Bean
	@ConditionalOnEnabledFilter
	public StripPrefixGatewayFilterFactory stripPrefixGatewayFilterFactory() {
		logger.info("ConditionalOnEnabledFilter -> GatewayAutoConfiguration#stripPrefixGatewayFilterFactory");
		return new StripPrefixGatewayFilterFactory();
	}

	@Bean
	@ConditionalOnEnabledFilter
	public RequestHeaderToRequestUriGatewayFilterFactory requestHeaderToRequestUriGatewayFilterFactory() {
		logger.info("ConditionalOnEnabledFilter -> GatewayAutoConfiguration#requestHeaderToRequestUriGatewayFilterFactory");
		return new RequestHeaderToRequestUriGatewayFilterFactory();
	}

	@Bean
	@ConditionalOnEnabledFilter
	public RequestSizeGatewayFilterFactory requestSizeGatewayFilterFactory() {
		logger.info("ConditionalOnEnabledFilter -> GatewayAutoConfiguration#requestSizeGatewayFilterFactory");
		return new RequestSizeGatewayFilterFactory();
	}

	@Bean
	@ConditionalOnEnabledFilter
	public RequestHeaderSizeGatewayFilterFactory requestHeaderSizeGatewayFilterFactory() {
		logger.info("ConditionalOnEnabledFilter -> GatewayAutoConfiguration#requestHeaderSizeGatewayFilterFactory");
		return new RequestHeaderSizeGatewayFilterFactory();
	}

	@Bean
	public GzipMessageBodyResolver gzipMessageBodyResolver() {
		return new GzipMessageBodyResolver();
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(HttpClient.class)
	protected static class NettyConfiguration {

		protected final Log logger = LogFactory.getLog(getClass());

		@Bean
		@ConditionalOnProperty(name = "spring.cloud.gateway.httpserver.wiretap")
		public NettyWebServerFactoryCustomizer nettyServerWiretapCustomizer(
				Environment environment, ServerProperties serverProperties) {
			return new NettyWebServerFactoryCustomizer(environment, serverProperties) {
				@Override
				public void customize(NettyReactiveWebServerFactory factory) {
					factory.addServerCustomizers(httpServer -> httpServer.wiretap(true));
					super.customize(factory);
				}
			};
		}

		// 自定义请求客户端
		@Bean
		@ConditionalOnMissingBean
		public HttpClient gatewayHttpClient(HttpClientProperties properties,
				List<HttpClientCustomizer> customizers) {

			// configure pool resources
			HttpClientProperties.Pool pool = properties.getPool();

			ConnectionProvider connectionProvider;
			if (pool.getType() == DISABLED) {
				connectionProvider = ConnectionProvider.newConnection();
			}
			else if (pool.getType() == FIXED) {
				connectionProvider = ConnectionProvider.fixed(pool.getName(),
						pool.getMaxConnections(), pool.getAcquireTimeout(),
						pool.getMaxIdleTime(), pool.getMaxLifeTime());
			}
			else {
				connectionProvider = ConnectionProvider.elastic(pool.getName(),
						pool.getMaxIdleTime(), pool.getMaxLifeTime());
			}

			logger.info("netty 自己的 HttpClient -> NettyConfiguration#gatewayHttpClient");
			HttpClient httpClient = HttpClient.create(connectionProvider)
					// TODO: move customizations to HttpClientCustomizers
					.httpResponseDecoder(spec -> {
						if (properties.getMaxHeaderSize() != null) {
							// cast to int is ok, since @Max is Integer.MAX_VALUE
							spec.maxHeaderSize(
									(int) properties.getMaxHeaderSize().toBytes());
						}
						if (properties.getMaxInitialLineLength() != null) {
							// cast to int is ok, since @Max is Integer.MAX_VALUE
							spec.maxInitialLineLength(
									(int) properties.getMaxInitialLineLength().toBytes());
						}
						return spec;
					}).tcpConfiguration(tcpClient -> {

						if (properties.getConnectTimeout() != null) {
							tcpClient = tcpClient.option(
									ChannelOption.CONNECT_TIMEOUT_MILLIS,
									properties.getConnectTimeout());
						}

						// configure proxy if proxy host is set.
						HttpClientProperties.Proxy proxy = properties.getProxy();

						if (StringUtils.hasText(proxy.getHost())) {

							tcpClient = tcpClient.proxy(proxySpec -> {
								ProxyProvider.Builder builder = proxySpec
										.type(ProxyProvider.Proxy.HTTP)
										.host(proxy.getHost());

								PropertyMapper map = PropertyMapper.get();

								map.from(proxy::getPort).whenNonNull().to(builder::port);
								map.from(proxy::getUsername).whenHasText()
										.to(builder::username);
								map.from(proxy::getPassword).whenHasText()
										.to(password -> builder.password(s -> password));
								map.from(proxy::getNonProxyHostsPattern).whenHasText()
										.to(builder::nonProxyHosts);
							});
						}
						return tcpClient;
					});

			HttpClientProperties.Ssl ssl = properties.getSsl();
			if ((ssl.getKeyStore() != null && ssl.getKeyStore().length() > 0)
					|| ssl.getTrustedX509CertificatesForTrustManager().length > 0
					|| ssl.isUseInsecureTrustManager()) {
				httpClient = httpClient.secure(sslContextSpec -> {
					// configure ssl
					SslContextBuilder sslContextBuilder = SslContextBuilder.forClient();

					X509Certificate[] trustedX509Certificates = ssl
							.getTrustedX509CertificatesForTrustManager();
					if (trustedX509Certificates.length > 0) {
						sslContextBuilder = sslContextBuilder
								.trustManager(trustedX509Certificates);
					}
					else if (ssl.isUseInsecureTrustManager()) {
						sslContextBuilder = sslContextBuilder
								.trustManager(InsecureTrustManagerFactory.INSTANCE);
					}

					try {
						sslContextBuilder = sslContextBuilder
								.keyManager(ssl.getKeyManagerFactory());
					}
					catch (Exception e) {
						logger.error(e);
					}

					sslContextSpec.sslContext(sslContextBuilder)
							.defaultConfiguration(ssl.getDefaultConfigurationType())
							.handshakeTimeout(ssl.getHandshakeTimeout())
							.closeNotifyFlushTimeout(ssl.getCloseNotifyFlushTimeout())
							.closeNotifyReadTimeout(ssl.getCloseNotifyReadTimeout());
				});
			}

			if (properties.isWiretap()) {
				httpClient = httpClient.wiretap(true);
			}

			if (!CollectionUtils.isEmpty(customizers)) {
				customizers.sort(AnnotationAwareOrderComparator.INSTANCE);
				for (HttpClientCustomizer customizer : customizers) {
					httpClient = customizer.customize(httpClient);
				}
			}
			logger.info("返回使用 Netty 封账过得 HttpClient -> NettyConfiguration#gatewayHttpClient");
			return httpClient;
		}

		@Bean
		public HttpClientProperties httpClientProperties() {
			logger.info("get httpClientProperties -> NettyConfiguration#httpClientProperties");
			return new HttpClientProperties();
		}

		@Bean
		@ConditionalOnEnabledGlobalFilter
		public NettyRoutingFilter routingFilter(HttpClient httpClient,
				ObjectProvider<List<HttpHeadersFilter>> headersFilters,
				HttpClientProperties properties) {
			logger.info("执行 routing 过滤 -> NettyConfiguration#routingFilter");
			return new NettyRoutingFilter(httpClient, headersFilters, properties);
		}

		@Bean
		@ConditionalOnEnabledGlobalFilter
		public NettyWriteResponseFilter nettyWriteResponseFilter(
				GatewayProperties properties) {
			logger.info("Netty 写返回过滤器 -> NettyConfiguration#nettyWriteResponseFilter");
			return new NettyWriteResponseFilter(properties.getStreamingMediaTypes());
		}

		@Bean
		public ReactorNettyWebSocketClient reactorNettyWebSocketClient(
				HttpClientProperties properties, HttpClient httpClient) {
			logger.info("响应式ws客户端 -> NettyConfiguration#reactorNettyWebSocketClient");
			ReactorNettyWebSocketClient webSocketClient = new ReactorNettyWebSocketClient(
					httpClient);
			if (properties.getWebsocket().getMaxFramePayloadLength() != null) {
				webSocketClient.setMaxFramePayloadLength(
						properties.getWebsocket().getMaxFramePayloadLength());
			}
			webSocketClient.setHandlePing(properties.getWebsocket().isProxyPing());
			return webSocketClient;
		}

		@Bean
		public ReactorNettyRequestUpgradeStrategy reactorNettyRequestUpgradeStrategy(
				HttpClientProperties httpClientProperties) {
			ReactorNettyRequestUpgradeStrategy requestUpgradeStrategy = new ReactorNettyRequestUpgradeStrategy();

			HttpClientProperties.Websocket websocket = httpClientProperties
					.getWebsocket();
			PropertyMapper map = PropertyMapper.get();
			map.from(websocket::getMaxFramePayloadLength).whenNonNull()
					.to(requestUpgradeStrategy::setMaxFramePayloadLength);
			map.from(websocket::isProxyPing).to(requestUpgradeStrategy::setHandlePing);
			return requestUpgradeStrategy;
		}

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass({ HystrixObservableCommand.class, RxReactiveStreams.class })
	protected static class HystrixConfiguration {

		@Bean
		@ConditionalOnEnabledFilter
		public HystrixGatewayFilterFactory hystrixGatewayFilterFactory(
				ObjectProvider<DispatcherHandler> dispatcherHandler) {
			return new HystrixGatewayFilterFactory(dispatcherHandler);
		}

		@Bean
		@ConditionalOnMissingBean(FallbackHeadersGatewayFilterFactory.class)
		@ConditionalOnEnabledFilter
		public FallbackHeadersGatewayFilterFactory fallbackHeadersGatewayFilterFactory() {
			return new FallbackHeadersGatewayFilterFactory();
		}

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(Health.class)
	protected static class GatewayActuatorConfiguration {

		protected final Log logger = LogFactory.getLog(getClass());

		@Bean
		@ConditionalOnProperty(name = "spring.cloud.gateway.actuator.verbose.enabled",
				matchIfMissing = true)
		@ConditionalOnAvailableEndpoint
		public GatewayControllerEndpoint gatewayControllerEndpoint(
				List<GlobalFilter> globalFilters,
				List<GatewayFilterFactory> gatewayFilters,
				List<RoutePredicateFactory> routePredicates,
				RouteDefinitionWriter routeDefinitionWriter, RouteLocator routeLocator,
				RouteDefinitionLocator routeDefinitionLocator) {
			logger.info("gatewayControllerEndpoint -> GatewayActuatorConfiguration#gatewayControllerEndpoint");
			return new GatewayControllerEndpoint(globalFilters, gatewayFilters,
					routePredicates, routeDefinitionWriter, routeLocator,
					routeDefinitionLocator);
		}

		@Bean
		@Conditional(OnVerboseDisabledCondition.class)
		@ConditionalOnAvailableEndpoint
		public GatewayLegacyControllerEndpoint gatewayLegacyControllerEndpoint(
				RouteDefinitionLocator routeDefinitionLocator,
				List<GlobalFilter> globalFilters,
				List<GatewayFilterFactory> gatewayFilters,
				List<RoutePredicateFactory> routePredicates,
				RouteDefinitionWriter routeDefinitionWriter, RouteLocator routeLocator) {
			logger.info("gatewayLegacyControllerEndpoint -> GatewayActuatorConfiguration#gatewayLegacyControllerEndpoint");
			return new GatewayLegacyControllerEndpoint(routeDefinitionLocator,
					globalFilters, gatewayFilters, routePredicates, routeDefinitionWriter,
					routeLocator);
		}

	}

	private static class OnVerboseDisabledCondition extends NoneNestedConditions {

		OnVerboseDisabledCondition() {
			super(ConfigurationPhase.REGISTER_BEAN);
		}

		@ConditionalOnProperty(name = "spring.cloud.gateway.actuator.verbose.enabled",
				matchIfMissing = true)
		static class VerboseDisabled {

		}

	}

}
