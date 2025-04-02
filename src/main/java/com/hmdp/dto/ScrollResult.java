package com.hmdp.dto;

import lombok.Data;

import java.util.List;

//TODO 滚动分页通用返回值数据类
// 首先是list，表示查询出来的结果；minTime：查询的最小值，作为下一次查询的最大值；offset：查询结果中最小值数量，在下一次查询中需要先跳过这些，在查询相应数量的结果
// minTime、offset传递给前端，前端收到后，作为下一次滚动分页查询请求的参数再次传递回后端。滚动分页就这样一直持续下去。
// 第一次的时候，minTime为前端获取的当前时间戳、offset不给，服务器默认0
@Data
public class ScrollResult {
    private List<?> list;//类型不确定，使用?泛型
    private Long minTime;//查询的最小值，作为下一次查询的最大值
    private Integer offset;//查询结果中最小值数量，在下一次查询中需要先跳过这些，在查询相应数量的结果
}
