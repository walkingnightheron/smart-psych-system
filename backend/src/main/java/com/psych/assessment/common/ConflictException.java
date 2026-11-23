package com.psych.assessment.common;

public class ConflictException extends BizException {

    public ConflictException(String message, Object conflictData) {
        super(409, message, conflictData);
    }
}
