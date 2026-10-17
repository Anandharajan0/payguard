package com.anandharajan.payguard.resolution;

public interface CaptureProvenanceRepository {

    CaptureProvenance find(String captureId);

    void register(CaptureProvenance provenance);
}
