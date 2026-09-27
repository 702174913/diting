package com.diting.service.report;

import com.diting.entity.RecognitionTask;
import com.diting.entity.ReportVersion;
import com.diting.mapper.RecognitionTaskMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 一次性验证：把真实库里的任务 1046 按**当前启用的规则集**重判一遍（服务层直算，不走 HTTP）。
 *
 * 与后台 `POST /api/admin/recordings/{id}/reanalyze` 走同一条服务路径
 * （`ReportService.analyzeWithoutRoles` → `ReportAnalysisService.publish`），
 * 差别只在绕过 HTTP 层与权限校验——接口的权限/审计/异常语义由
 * `AdminRecordingReanalyzeTest` + `ReportAnalysisServiceTest` 覆盖。
 *
 * **默认 @Disabled**：它连真实库、会真的追加一个报告版本，绝不能被 `mvn test` 顺手跑到。
 * 需要时手动开启，并显式提供真实凭据（OS 环境变量优先级高于 src/test/resources/application.properties 里的测试值）：
 *
 * <pre>
 * export $(grep -v '^#' runtime.env | xargs)   # DITING_DB_PASSWORD / DITING_JWT_SECRET / ...
 * mvn -Dtest=ReanalyzeLiveTest -Dditing.pipeline.tick-interval-ms=3600000 \
 *     -Dditing.export.tick-interval-ms=3600000 -Dditing.retention.tick-interval-ms=3600000 test
 * </pre>
 *
 * 调度间隔调大只是少跑几个 tick：三个 @Scheduled 在启动时都会先跑一次，
 * 而当前库里没有 QUEUED/TRANSCRIBING 任务、也没有待处理的到期清理，所以那一次是空转。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@org.junit.jupiter.api.Disabled("手工验证用：连真实库并追加报告版本，需要时手动开启")
class ReanalyzeLiveTest {

    private static final long TASK_ID = 1046L;

    @Autowired
    private ReportService reportService;

    @Autowired
    private RecognitionTaskMapper taskMapper;

    @Test
    void reanalyzeRealTask() {
        RecognitionTask task = taskMapper.selectById(TASK_ID);
        assertThat(task).isNotNull();
        assertThat(task.getDeletedAt()).isNull();

        ReportVersion version = reportService.analyzeWithoutRoles(task);

        System.out.printf("task=%d 报告版本=v%d 规则集=%s 风险分=%s 合规分=%s 状态=%s%n",
            TASK_ID, version.getVersion(), version.getRuleSetVersion(),
            version.getRiskScore(), version.getComplianceScore(), version.getStatus());

        assertThat(version.getStatus()).isEqualTo("PUBLISHED");
        assertThat(version.getVersion()).isGreaterThan(1L);
        // 48s 风险话术样本：威胁性后果(25, 去重) + 联系第三人(20) + 4 条 must-have 缺失(15/10/15/10)
        assertThat(version.getRiskScore()).isEqualTo(95);
        assertThat(version.getComplianceScore()).isEqualTo(5);
    }
}
