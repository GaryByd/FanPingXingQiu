package com.hmdp.dto;

import io.netty.handler.codec.dns.DnsResponse;
import lombok.Data;

@Data
public class LoginFormDTO {
    private String phone;
    private String code;
    private String password;

}
