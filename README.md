# SwingFit  
### AI 기반 스마트 골프 스윙 분석 및 비거리 예측 시스템

2025 한신대학교 컴퓨터공학부 캡스톤 디자인 프로젝트

---

## 📌 프로젝트 소개  
**SwingFit**은 **스마트폰 하나로 스윙 분석·비거리 예측·자연어 피드백**을 제공하는 골프 코칭 서비스이다.  
스마트폰에서 직접 수행되는 **온디바이스 AI 모델(YOLOv8n TFLite)**이 공·클럽의 움직임을 탐지하고,  
발사각·초기 속도·탄도 정보를 기반으로 **비거리를 실시간 예측**한다.  

또한 클라우드에서 동작하는 **LLM(Gemini 2.5 Pro)**이 사용자의 스윙 데이터를 요약하고,  
자세·템포·임팩트에 대한 **개선 포인트를 자연어로 피드백**한다.  
촬영 기록은 **Firebase**로 동기화되며, 날짜·클럽별 비교 및 세션 단위 분석이 가능하다.

---

## 🎯 주요 특징

### ✔ 온디바이스 AI  
- YOLOv8n FP16 TFLite 모델  
- XNNPACK 기반 최적화  
- 4K/120FPS 입력 또는 720p/120FPS Camera2 파이프라인  
- 공·클럽·손·스윙 이벤트 탐지  

### ✔ 비거리 예측  
- 임팩트 속도·발사각 기반 물리학 수식  
- 항력 포함 근사식 + ML 기반 보정  
- 세션별 결과 카드 자동 생성  

### ✔ LLM 기반 코칭  
- Gemini 2.5 Pro  
- 자세·임팩트·템포·탄도에 대한 자연어 피드백  
- “이번 스윙은 상향 타격이 강했습니다.” 같은 조언 생성  

### ✔ 클라우드 동기화  
- Firebase Auth / Firestore / Storage  
- Functions에서 LLM 자동 요약  
- 데이터 분석·기록·비교 가능  

### ✔ UI/UX  
- Material Design 3  
- ExoPlayer 기반 구간 분석  
- 모바일 최적화 UI  

---

## 🛠 기술 스택

| 구분 | 내용 |
|------|------|
| Mobile | Android Studio(Kotlin), CameraX, Camera2 API, ExoPlayer |
| AI Model | YOLOv8n FP16/INT8 TFLite, TensorFlow Lite 2.14, tflite-support 0.4.4 |
| Optimization | XNNPACK, ROI cropping, frame sampling |
| Cloud | Firebase Auth · Firestore · Storage · Functions |
| LLM | Google Gemini 2.5 Pro |
| UX(Web) | Tailwind · MD3 · Alpine.js |
| Demo Page | GitHub Pages, Charts.js |

---

## 📐 시스템 구조

### 🟩 온디바이스  
- CameraX / Camera2API 영상 입력  
- YOLOv8n 객체 탐지  
- 임팩트 기준 속도·각도·궤적 계산  
- 비거리 예측 모델  
- UI 표시  

### 🟪 클라우드  
- Firestore / Storage 업로드  
- Functions → LLM 분석  
- Gemini 2.5 Pro 코칭 피드백 생성  

---

## 📊 성능 지표

### 객체 검출(mAP)
- mAP@50(Ball): 93  
- mAP@50-95(Ball): 84  
- mAP@50(Swing): 88  
- mAP@50-95(Swing): 80  

### 지연(Latency)
- 평균 FPS: 28  
- 평균 추론 지연: ~38ms  

### 비거리 예측
- 실측: 90–245m  
- 오차: 대부분 4.5–5.5%  
- 150–160m: ±8.7%  
- 예측+오차 범위 시각화 지원  

---

## 📱 앱 화면 구성
- 홈 / 기록 / 설정  
- 비거리 분석(ROI → 임팩트 → 예측)  
- 스윙 분석(궤적·임팩트·템포)  
- 혼합 모드  
- 분석 탭 + LLM 피드백  
- 필드 테스트 이미지 포함  

---

## 🧪 데모 영상  
YouTube: https://www.youtube.com/watch?v=jndrXWHrAyA  
GitHub Pages: https://habinsong.github.io/SwingFit  

---

## 📦 저장소 구조
SwingFit/
├── app/
│   ├── ui/
│   ├── ml/
│   ├── data/
│   └── utils/
├── assets/
├── tflite_models/
├── functions/
├── docs/
├── index.html
└── README.md

---

## 👥 팀 구성

- 한신대학교 컴퓨터공학부  
  - 송하빈  
  - 주상혁  
  - 박승정  
- 지도교수: 박기홍  
- SW중심대학사업 지원

---

## 📬 Contact  
- Issues: https://github.com/habinsong/SwingFit/issues 
