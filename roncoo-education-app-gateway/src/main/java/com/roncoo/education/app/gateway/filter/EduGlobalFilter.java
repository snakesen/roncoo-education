package com.roncoo.education.app.gateway.filter;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.roncoo.education.common.core.base.BaseException;
import com.roncoo.education.common.core.enums.RedisPreEnum;
import com.roncoo.education.common.core.enums.ResultEnum;
import com.roncoo.education.common.core.tools.JSUtil;
import com.roncoo.education.common.core.tools.JWTUtil;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.factory.rewrite.CachedBodyOutputMessage;
import org.springframework.cloud.gateway.support.BodyInserterContext;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @author wujing
 */
@Slf4j
@Component
public class EduGlobalFilter implements GlobalFilter, Ordered {
    private static final Logger logger = LoggerFactory.getLogger(EduGlobalFilter.class);

    private static final String TOKEN = "token";
    private static final String USERNO = "userNo";

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String uri = request.getPath().value();
        if (uri.contains("/callback")) {
            // ?????????????????????????????????
            return chain.filter(exchange);
        }
        if (uri.contains("/api")) {
            // ????????????????????????/api????????????
            return chain.filter(exchange);
        }

        // ??????token
        Long userNo = getUserNoByToken(request);
        if (uri.contains("/pc") && !uri.contains("/system/pc/menu/user/list") && !uri.contains("/system/pc/menu/user/button/list")) {
            // ??????????????????
            if (!stringRedisTemplate.hasKey(RedisPreEnum.ADMINI_MENU.getCode().concat(userNo.toString()))) {
                throw new BaseException(ResultEnum.MENU_PAST);
            }
            String tk = stringRedisTemplate.opsForValue().get(RedisPreEnum.ADMINI_MENU.getCode().concat(userNo.toString()));
            // ???????????????????????????
            if (!checkUri(uri, tk)) {
                throw new BaseException(ResultEnum.MENU_NO);
            }
            // ???????????????????????????????????????
            stringRedisTemplate.opsForValue().set(RedisPreEnum.ADMINI_MENU.getCode().concat(userNo.toString()), tk, 1, TimeUnit.HOURS);
        }
        return request(exchange, chain, modifiedBody(exchange, userNo));
    }

    // ???????????????????????????
    private static Boolean checkUri(String uri, String tk) {
        List<String> menuVOList1 = JSUtil.parseArray(tk, String.class);
        if (StringUtils.hasText(uri) && uri.endsWith("/")) {
            uri = uri.substring(0, uri.length() - 1);
        }
        for (String s : menuVOList1) {
            if (s.contains(uri)) {
                return true;
            }
        }
        return false;
    }

    /**
     * ????????????order????????????????????????
     *
     * @return
     */
    @Override
    public int getOrder() {
        return 0;
    }


    private Mono<String> modifiedBody(ServerWebExchange serverWebExchange, Long userNo) {
        MediaType mediaType = serverWebExchange.getRequest().getHeaders().getContentType();
        ServerRequest serverRequest = ServerRequest.create(serverWebExchange, HandlerStrategies.withDefaults().messageReaders());
        return serverRequest.bodyToMono(String.class).flatMap(body -> {
            JSONObject bodyJson;
            if (MediaType.APPLICATION_FORM_URLENCODED.isCompatibleWith(mediaType)) {
                Map<String, Object> bodyMap = decodeBody(body);
                bodyJson = JSONUtil.parseObj(bodyMap);
            } else {
                bodyJson = JSONUtil.parseObj(body);
            }
            if (ObjectUtil.isNotNull(userNo)) {
                bodyJson.set(USERNO, userNo);
            }
            return Mono.just(JSONUtil.toJsonStr(bodyJson));
        });
    }

    private Map<String, Object> decodeBody(String body) {
        return Arrays.stream(body.split("&")).map(s -> s.split("=")).collect(Collectors.toMap(arr -> arr[0], arr -> arr[1]));
    }

    private Mono<Void> request(ServerWebExchange exchange, GatewayFilterChain chain, Mono<String> modifiedBody) {
        BodyInserter bodyInserter = BodyInserters.fromPublisher(modifiedBody, String.class);
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(exchange.getRequest().getHeaders());
        headers.remove(HttpHeaders.CONTENT_LENGTH);
        CachedBodyOutputMessage outputMessage = new CachedBodyOutputMessage(exchange, headers);
        return bodyInserter.insert(outputMessage, new BodyInserterContext())
                .then(Mono.defer(() -> {
                    ServerHttpRequestDecorator decorator =
                            new ServerHttpRequestDecorator(exchange.getRequest()) {
                                @Override
                                public HttpHeaders getHeaders() {
                                    return headers;
                                }

                                @Override
                                public Flux<DataBuffer> getBody() {
                                    return outputMessage.getBody();
                                }
                            };
                    return chain.filter(exchange.mutate().request(decorator).build());
                }));
    }

    private Long getUserNoByToken(ServerHttpRequest request) {
        // ??????
        String token = request.getHeaders().getFirst(TOKEN);
        if (StringUtils.isEmpty(token)) { // token????????????????????????????????????
            throw new BaseException("token???????????????????????????");
        }
        // ?????? token
        DecodedJWT jwt = null;
        try {
            jwt = JWTUtil.verify(token);
        } catch (Exception e) {
            logger.error("token?????????token={}", token.toString());
            throw new BaseException(ResultEnum.TOKEN_ERROR);
        }

        // ??????token
        if (null == jwt) {
            throw new BaseException(ResultEnum.TOKEN_ERROR);
        }
        Long userNo = JWTUtil.getUserNo(jwt);
        if (userNo <= 0) {
            throw new BaseException(ResultEnum.TOKEN_ERROR);
        }

        // ??????????????????????????????????????????????????????????????????
//        if (!stringRedisTemplate.hasKey(userNo.toString())) {
//            // ??????????????????????????????????????????1??????
//            throw new BaseException(ResultEnum.TOKEN_PAST);
//        }
//        // ?????????????????????token??????
//        String tk = stringRedisTemplate.opsForValue().get(userNo.toString());
//        if (!token.equals(tk)) {
//            // ???????????????????????????????????????????????????????????????
//            throw new BaseException(ResultEnum.REMOTE_ERROR);
//        }

        // ??????????????????token?????????
        stringRedisTemplate.opsForValue().set(userNo.toString(), token, 1, TimeUnit.HOURS);
        return userNo;
    }

}
