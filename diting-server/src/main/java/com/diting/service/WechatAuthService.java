package com.diting.service;

import com.diting.common.BizException;
import com.diting.common.ResultCode;
import com.diting.config.DitingProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/** 微信小程序登录：code2Session 换 openid（secret 只存服务端） */
@Service
public class WechatAuthService {

    private final RestClient restClient = RestClient.create();
    private final DitingProperties props;

    public WechatAuthService(DitingProperties props) {
        this.props = props;
    }

    @SuppressWarnings("unchecked")
    public String codeToOpenid(String code) {
        if (!configured()) {
            // 与 OSS/听悟一致：缺凭据一律 503，不去打真实接口，更不假装登录成功
            throw new BizException(ResultCode.NOT_CONFIGURED, "微信登录未配置");
        }
        String url = UriComponentsBuilder
            .fromHttpUrl("https://api.weixin.qq.com/sns/jscode2session")
            .queryParam("appid", props.getWechat().getAppid())
            .queryParam("secret", props.getWechat().getSecret())
            .queryParam("js_code", code)
            .queryParam("grant_type", "authorization_code")
            .build()
            .toUriString();
        Map<String, Object> body = restClient.get().uri(url).retrieve().body(Map.class);
        if (body == null || body.get("openid") == null) {
            // 只记错误码：响应体可能含 openid/session_key，属敏感信息，不得进日志与异常消息
            Object errcode = body == null ? null : body.get("errcode");
            throw new IllegalStateException("code2Session 失败（errcode=" + errcode + "）");
        }
        return (String) body.get("openid");
    }

    /** 凭据齐全才允许调用：占位值（“请替换”）与空值都视为未配置 */
    private boolean configured() {
        return isReal(props.getWechat().getAppid()) && isReal(props.getWechat().getSecret());
    }

    private static boolean isReal(String value) {
        return value != null && !value.isBlank() && !value.contains("请替换");
    }
}
