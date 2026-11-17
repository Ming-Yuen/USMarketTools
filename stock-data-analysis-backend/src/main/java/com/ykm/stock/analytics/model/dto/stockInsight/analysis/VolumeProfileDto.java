package com.ykm.stock.analytics.model.dto.stockInsight.analysis;
import java.util.List;
public record VolumeProfileDto(List<VolumeBinDto> bins, VolumeBinDto poc, List<VolumeBinDto> highVolumeNodes, List<VolumeBinDto> lowVolumeNodes, String approximationLabel) { public VolumeProfileDto { bins=bins==null?List.of():List.copyOf(bins); highVolumeNodes=highVolumeNodes==null?List.of():List.copyOf(highVolumeNodes); lowVolumeNodes=lowVolumeNodes==null?List.of():List.copyOf(lowVolumeNodes); } }
