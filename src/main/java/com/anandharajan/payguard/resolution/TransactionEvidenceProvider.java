package com.anandharajan.payguard.resolution;

public interface TransactionEvidenceProvider {

    TransactionEvidence find(String captureId);
}
