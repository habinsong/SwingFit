package com.example.model

data class SwingAnalysis(
    var id: String = "",
    // 🔽 리스트 썸네일용 필드 (Firestore에 imageUriLocal 저장됨)
    var thumbnailUri: String? = null,

    // 메타
    var createdAt: String? = null,     // "2025년 9월 25일 ..." 또는 포맷된 문자열
    var timestampMs: Long? = null,     // 1758804895376
    var imageUriLocal: String? = null, // 썸네일로 쓸 로컬 URI
    var club: String? = null,          // "드라이버"
    var environment: String? = null,   // 선택 사항

    // 분석 결과(전부 문자열)
    var swingType: String? = null,
    var overallFeedback: String? = null,
    var takeaway: String? = null,
    var transition: String? = null,
    var impact: String? = null,
    var followThrough: String? = null,
    var keyStrength: String? = null,
    var improvement: String? = null


)