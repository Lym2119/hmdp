package com.hmdp.dto;

import lombok.Data;

//用于非数据库的数据流通，所使用的对象，减少服务器内存压力+方式信息泄露

@Data
public class UserDTO {
    private Long id;
    private String nickName;
    private String icon;
}
