package com.example.model

/**
 * 분석 결과 공통 필드 베이스 클래스
 */
open class AnalysisBase(
    open var id: String? = null,
    open var thumbnailUri: String? = null
)