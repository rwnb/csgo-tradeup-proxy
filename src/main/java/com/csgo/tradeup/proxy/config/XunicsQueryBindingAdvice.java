package com.csgo.tradeup.proxy.config;

import com.csgo.tradeup.proxy.controller.xunics.param.XunicsQueryParam;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.context.request.NativeWebRequest;

/** Spring 的 ModelAttribute 不识别 JsonProperty，补齐 snake_case 到 Java 属性的绑定。 */
@ControllerAdvice(basePackages = "com.csgo.tradeup.proxy.controller.xunics")
public class XunicsQueryBindingAdvice {
    private final ObjectMapper mapper;
    public XunicsQueryBindingAdvice(ObjectMapper mapper) { this.mapper = mapper; }

    @InitBinder
    public void bindQuery(WebDataBinder binder, NativeWebRequest request) {
        if (!(binder.getTarget() instanceof XunicsQueryParam)) return;
        MutablePropertyValues values = new MutablePropertyValues();
        for (BeanPropertyDefinition property : mapper.getDeserializationConfig()
                .introspect(mapper.constructType(binder.getTarget().getClass())).findProperties()) {
            String value = request.getParameter(property.getName());
            if (value != null) values.add(property.getInternalName(), value);
        }
        binder.bind(values);
    }
}
