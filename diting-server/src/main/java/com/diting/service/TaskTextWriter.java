package com.diting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.diting.common.BizException;
import com.diting.common.ResultCode;
import com.diting.entity.RecognitionTask;
import com.diting.entity.Sentence;
import com.diting.mapper.RecognitionTaskMapper;
import com.diting.mapper.SentenceMapper;
import com.diting.mapper.TaskAssetMapper;
import com.diting.service.ocr.OcrParser;
import com.diting.service.tingwu.TranscriptionParser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 独立事务 Bean：禁止同类自调用绕过事务；所有文本写入与删除共用任务行锁。 */
@Service
public class TaskTextWriter {
    private final RecognitionTaskMapper tasks;
    private final SentenceMapper sentences;
    private final TaskAssetMapper assets;

    public TaskTextWriter(RecognitionTaskMapper tasks, SentenceMapper sentences, TaskAssetMapper assets) {
        this.tasks = tasks;
        this.sentences = sentences;
        this.assets = assets;
    }

    private void assertWritable(RecognitionTask snapshot) {
        RecognitionTask live = tasks.lockById(snapshot.getId());
        if (live == null || live.getDeletedAt() != null
            || !java.util.Objects.equals(live.getVersion(), snapshot.getVersion())
            || !("TRANSCRIBING".equals(live.getStatus()) || "PARSING".equals(live.getStatus()))) {
            throw new BizException(ResultCode.CONFLICT, "任务已删除或工作者版本已失效，拒绝写入文本");
        }
    }

    @Transactional
    public void saveAudio(RecognitionTask snapshot, TranscriptionParser.ParsedTranscription parsed) {
        assertWritable(snapshot);
        Long taskId = snapshot.getId();
        sentences.delete(new LambdaQueryWrapper<Sentence>().eq(Sentence::getTaskId, taskId)
            .isNull(Sentence::getSourceAssetId));
        for (var item : parsed.sentences()) {
            Sentence row = new Sentence();
            row.setTaskId(taskId);
            row.setSequence(item.sequence());
            row.setSpeakerId(item.speakerId());
            row.setStartMs(item.startMs());
            row.setEndMs(item.endMs());
            row.setText(item.text());
            row.setFlagged(false);
            sentences.insert(row);
        }
        if (parsed.durationMs() > 0) {
            tasks.updateDuration(taskId, (int) Math.round(parsed.durationMs() / 1000.0));
        }
        rebuildImages(taskId);
    }

    @Transactional
    public void refreshImages(RecognitionTask snapshot) {
        assertWritable(snapshot);
        rebuildImages(snapshot.getId());
    }

    private void rebuildImages(Long taskId) {
        sentences.delete(new LambdaQueryWrapper<Sentence>().eq(Sentence::getTaskId, taskId)
            .isNotNull(Sentence::getSourceAssetId));
        int sequence = sentences.selectMaxSequence(taskId);
        for (var asset : assets.selectByTask(taskId)) {
            if (!asset.isImage() || !"DONE".equals(asset.getOcrStatus())
                || asset.getOcrText() == null || asset.getOcrText().isBlank()) continue;
            // 存量结果损坏不能静默发布部分报告；抛异常整体回滚，留待修复/重试。
            for (String line : OcrParser.parse(asset.getOcrText()).lines()) {
                if (line == null || line.isBlank()) continue;
                Sentence row = new Sentence();
                row.setTaskId(taskId);
                row.setSourceAssetId(asset.getId());
                row.setSequence(++sequence);
                row.setStartMs(0L);
                row.setEndMs(0L);
                row.setText(line.trim());
                row.setFlagged(false);
                sentences.insert(row);
            }
        }
    }
}
