package com.diting.common;

import java.util.List;

/** 分页结果（开发文档 §7.1）：{items,total,page,pageSize} */
public record PageResult<T>(List<T> items, long total, int page, int pageSize) {

    public static <T> PageResult<T> of(List<T> items, long total, PageQuery query) {
        return new PageResult<>(items, total, query.page(), query.pageSize());
    }
}
