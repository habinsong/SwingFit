package com.example.model

data class BallAnalysis(
    var totalDistance: String? = null,   // "152미터"
    var carryDistance: String? = null,   // "145미터" 형태
    var launchAngle: String? = null,     // "19.5도"
    var ballSpeed: String? = null,       // "48.2m/s"
    var backspin: String? = null,        // "6300 RPM"
    var club: String? = null,            // "아이언 7"  (← clubType가 아니라 club)
    var smashFactor: String? = null,     // "1.31"
    var apexHeight: String? = null,      // "26미터"
    var clubHeadSpeed: String? = null,   // "36.8m/s"
    var swingPath: String? = null,       // "안-아웃"
    var swingTempo: String? = null,      // "3:1"
    var feedback: String? = null,        // 분석 코멘트
    var environment: String? = null,     // "실외 골프 연습장"
    var createdAt: String? = null,       // "2025년 9월 25일 ..."
    var day: String? = null,             // "20250925"
    var timestampMs: Long? = null,       // 1758811850699
    var ownerUid: String? = null         // Lo3NgG...


) : AnalysisBase()