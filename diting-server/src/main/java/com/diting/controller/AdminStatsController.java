package com.diting.controller;

import com.diting.common.AdminRole;
import com.diting.common.R;
import com.diting.config.RequireRole;
import com.diting.service.stats.StatsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 真实统计（开发文档 §7.3、§10）。
 * ADMIN/OPERATOR/INVESTOR 都可读聚合；**投资人拿不到任何个体明细**——
 * 本接口只返回聚合数字与口径元数据，个体数据在 /api/admin/recordings（INVESTOR 403）。
 */
@RestController
@RequestMapping("/api/admin/stats")
public class AdminStatsController {

    private final StatsService statsService;

    public AdminStatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/{section}")
    @RequireRole({AdminRole.ADMIN, AdminRole.OPERATOR, AdminRole.INVESTOR})
    public R<StatsService.Section> section(@PathVariable String section,
                                           @RequestParam(required = false) String period,
                                           @RequestParam(required = false)
                                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                           @RequestParam(required = false)
                                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate[] range = StatsService.resolveRange(period, from, to);
        return R.ok(statsService.of(section, period, range[0], range[1]));
    }
}
