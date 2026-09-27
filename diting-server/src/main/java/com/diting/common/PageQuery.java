package com.diting.common;

/**
 * 分页参数（开发文档 §7.1）：page 从 1 开始，默认 20，上限 100。
 * 越界直接 422，避免各接口各写一套默认值。
 */
public record PageQuery(int page, int pageSize) {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    public static PageQuery of(Integer page, Integer pageSize) {
        int p = page == null ? 1 : page;
        int s = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        if (p < 1) {
            throw new BizException(ResultCode.UNPROCESSABLE, "page 从 1 开始");
        }
        if (s < 1 || s > MAX_PAGE_SIZE) {
            throw new BizException(ResultCode.UNPROCESSABLE, "pageSize 需在 1-" + MAX_PAGE_SIZE + " 之间");
        }
        return new PageQuery(p, s);
    }

    public long offset() {
        return (long) (page - 1) * pageSize;
    }
}
