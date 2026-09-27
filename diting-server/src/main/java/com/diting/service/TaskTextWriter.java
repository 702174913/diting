package com.diting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.diting.common.BizException;
import com.diting.common.ResultCode;
import com.diting.entity.RecognitionTask;
import com.diting.entity.Sentence;
import com.diting.mapper.RecognitionTaskMapper;
import com.diting.mapper.SentenceMapper;
import com.diting.mapper.TaskAssetMapper;
import com.diting.service.tingwu.TranscriptionParser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

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
            // ocr_text 是**归一化后的行文本**（管线按 '\n' 拼接）：同一口径切行即可重建句子。
            // 这里曾经把它当 RecognizeGeneral 的 Data JSON 二次解析，而管线存的是纯文本，
            // 于是真实链路上必然抛解析异常——图片句子永远重建不出来，口径已统一，别再改回 JSON。
            for (String line : splitOcrLines(asset.getOcrText())) {
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

    /** ocr_text 的切行口径（与 TaskPipelineService 落库时一致）：'\n' 分隔、去首尾空白、丢掉空行 */
    static List<String> splitOcrLines(String text) {
        return Arrays.stream(text.split("\\r\\n|\\r|\\n"))
            .map(String::trim)
            .filter(line -> !line.isEmpty())
            .toList();
    }
}
