package com.diting.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.diting.common.BizException;
import com.diting.common.ResultCode;
import com.diting.entity.RecognitionTask;
import com.diting.mapper.RecognitionTaskMapper;
import com.diting.mapper.SentenceMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AUTH-06：他人资源一律 404，不泄露资源是否存在。
 * 使用真实服务逻辑 + 桩 mapper，不连数据库。
 */
class RecognitionTaskOwnershipTest {

    private RecognitionTaskMapper taskMapper;
    private RecognitionTaskService service;

    @BeforeEach
    void setUp() {
        taskMapper = mock(RecognitionTaskMapper.class);
        service = new RecognitionTaskService(taskMapper, mock(SentenceMapper.class),
            mock(TingwuService.class), mock(ConfigService.class), mock(QuotaService.class),
            mock(UploadSessionService.class), mock(com.diting.service.lifecycle.RetentionService.class),
            mock(com.diting.mapper.TaskAssetMapper.class), new com.diting.config.DitingProperties(),
            mock(com.diting.mapper.UserMapper.class), mock(OcrService.class));
    }

    private RecognitionTask task(Long id, Long ownerId) {
        RecognitionTask task = new RecognitionTask();
        task.setId(id);
        task.setUserId(ownerId);
        task.setStatus("COMPLETED");
        task.setOssObject("audio/fixture.m4a");
        task.setCreatedAt(LocalDateTime.now());
        return task;
    }

    @Test
    @DisplayName("AUTH-06-1 访问他人任务：404，且文案不区分「不存在」与「不属于你」")
    void otherUsersTaskIsNotFound() {
        when(taskMapper.selectById(100L)).thenReturn(task(100L, 2L));

        assertThatThrownBy(() -> service.requireOwned(100L, 1L))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.NOT_FOUND);
    }

    @Test
    @DisplayName("AUTH-06-2 不存在的任务与越权返回同样的错误码")
    void missingTaskSameError() {
        when(taskMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.requireOwned(999L, 1L))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.NOT_FOUND);
    }

    @Test
    @DisplayName("S09 墓碑：已删除的记录对读取表现为 404")
    void deletedTaskIsNotFound() {
        RecognitionTask deleted = task(100L, 1L);
        deleted.setDeletedAt(java.time.LocalDateTime.now());
        when(taskMapper.selectById(100L)).thenReturn(deleted);

        assertThatThrownBy(() -> service.requireOwned(100L, 1L))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getResultCode())
            .isEqualTo(ResultCode.NOT_FOUND);
    }

    @Test
    @DisplayName("AUTH-06-3 本人任务正常返回")
    void ownTaskAccessible() {
        when(taskMapper.selectById(100L)).thenReturn(task(100L, 1L));

        assertThat(service.requireOwned(100L, 1L).getId()).isEqualTo(100L);
    }

    @Test
    @DisplayName("列表只查本人数据（查询条件带 user_id）")
    void listIsScopedToOwner() {
        // 纯单元测试里 LambdaQueryWrapper 需要先注册实体元信息，否则取 SQL 片段会报 lambda cache
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            RecognitionTask.class);
        when(taskMapper.selectList(any())).thenReturn(List.of(task(100L, 1L)));

        List<RecognitionTask> tasks = service.listOfUser(1L);

        assertThat(tasks).hasSize(1);
        // 断言查询条件确实带上了 user_id（取最后一次调用的 wrapper 参数）
        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<RecognitionTask>>
            captor = org.mockito.ArgumentCaptor.forClass(
            com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        org.mockito.Mockito.verify(taskMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("user_id");
    }
}
