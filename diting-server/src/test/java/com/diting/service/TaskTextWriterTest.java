package com.diting.service;

import com.diting.common.BizException;
import com.diting.entity.RecognitionTask;
import com.diting.entity.Sentence;
import com.diting.mapper.RecognitionTaskMapper;
import com.diting.mapper.SentenceMapper;
import com.diting.mapper.TaskAssetMapper;
import com.diting.service.tingwu.TranscriptionParser;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class TaskTextWriterTest {
    final RecognitionTaskMapper tasks = mock(RecognitionTaskMapper.class);
    final SentenceMapper sentences = mock(SentenceMapper.class);
    final TaskAssetMapper assets = mock(TaskAssetMapper.class);
    final TaskTextWriter writer = new TaskTextWriter(tasks, sentences, assets);

    private RecognitionTask task() {
        RecognitionTask row = new RecognitionTask();
        row.setId(1L); row.setVersion(5L); row.setStatus("PARSING");
        return row;
    }
    private TranscriptionParser.ParsedTranscription parsed() {
        return new TranscriptionParser.ParsedTranscription(
            List.of(new TranscriptionParser.ParsedSentence(1, 0, 0, 1000, "原文", List.of())), 1000, 1);
    }
    @Test void deletedTaskRejectsBothAudioAndImageRewrites() {
        RecognitionTask live = task(); live.setDeletedAt(LocalDateTime.now());
        when(tasks.lockById(1L)).thenReturn(live);
        assertThatThrownBy(() -> writer.saveAudio(task(), parsed())).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> writer.refreshImages(task())).isInstanceOf(BizException.class);
        verifyNoInteractions(sentences, assets);
        verify(tasks, never()).updateDuration(any(), any());
    }
    @Test void staleWorkerCannotWriteIntoNewGeneration() {
        RecognitionTask live = task(); live.setVersion(6L);
        when(tasks.lockById(1L)).thenReturn(live);
        assertThatThrownBy(() -> writer.saveAudio(task(), parsed())).isInstanceOf(BizException.class);
        verifyNoInteractions(sentences, assets);
    }
    @Test void completedTaskTextIsImmutableToOldParser() {
        RecognitionTask live = task(); live.setStatus("COMPLETED");
        when(tasks.lockById(1L)).thenReturn(live);
        assertThatThrownBy(() -> writer.refreshImages(task())).isInstanceOf(BizException.class);
        verifyNoInteractions(sentences, assets);
    }
    @Test void taskLockPrecedesSentenceWritesAndDuration() {
        when(tasks.lockById(1L)).thenReturn(task());
        writer.saveAudio(task(), parsed());
        var order = inOrder(tasks, sentences);
        order.verify(tasks).lockById(1L);
        order.verify(sentences).delete(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        order.verify(sentences).insert(any(Sentence.class));
        order.verify(tasks).updateDuration(1L, 1);
    }
    @Test void failedSentenceWriteRollsBackWholeReplacement() {
        when(tasks.lockById(1L)).thenReturn(task());
        when(sentences.insert(any(Sentence.class))).thenThrow(new IllegalStateException("DB"));
        var tx = mock(org.springframework.transaction.PlatformTransactionManager.class);
        var status = mock(org.springframework.transaction.TransactionStatus.class);
        when(tx.getTransaction(any())).thenReturn(status);
        var proxy = new org.springframework.aop.framework.ProxyFactory(writer);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(tx,
            new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        TaskTextWriter proxied = (TaskTextWriter) proxy.getProxy();
        assertThatThrownBy(() -> proxied.saveAudio(task(), parsed())).isInstanceOf(IllegalStateException.class);
        verify(tx).rollback(status);
        verify(tx, never()).commit(any());
    }
}
