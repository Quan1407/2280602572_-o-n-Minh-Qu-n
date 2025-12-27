# Blog Cá Nhân: Doan Minh Quan

Dự án blog cá nhân được xây dựng phục vụ cho đồ án học phần Công nghệ Thông tin.

## Chạy web + AI chat trả lời thật (trên web/localhost)

Trang này là web tĩnh (HTML). Nếu bạn muốn **AI trả lời thật trên web** (Gemini), bạn cần chạy kèm **server proxy** để:
- Giữ **API key** ở phía server (không lộ ra frontend)
- Cung cấp endpoint `POST /api/chat` cho trang `ai-assistant.html`

Mình đã thêm server Java 22 (không cần Maven/Node) ở thư mục `server/`.

### 1) Lấy Gemini API key
- Vào Google AI Studio và tạo API key (bạn chỉ cần 1 key).
- Bạn **không** dán key vào file HTML.

### 2) Chạy local (Windows PowerShell)
Tại thư mục dự án `D:\blog`:

```powershell
cd D:\blog
$env:GEMINI_API_KEY="YOUR_KEY_HERE"
.\server\run.ps1
```

Mở trình duyệt:
- `http://localhost:8000/ai-assistant.html`

### 3) Đưa lên web (deploy)
Bạn cần host được Java process (VPS/Render/Railway/Any host hỗ trợ Java).

- **Build**:
  - `javac -encoding UTF-8 -d out server/Json.java server/Main.java`
- **Run**:
  - `java -cp out Main`
- **Environment variables**:
  - `GEMINI_API_KEY`: bắt buộc
  - `GEMINI_MODEL`: tuỳ chọn (mặc định `gemini-1.5-flash`)
  - `PORT`: tuỳ chọn (host thường tự set)

Sau khi deploy, truy cập domain của bạn; trang `ai-assistant.html` sẽ tự gọi `/api/chat` trên cùng domain.

## Thông tin kỹ thuật

- **Công cụ xây dựng:** Static Site Generation (SSG) concept.
- **Frontend:** HTML5, CSS3, Tailwind CSS (CDN).
- **Phông chữ:** Inter (Google Fonts).
- **Quản lý mã nguồn:** GitHub Repository.
- **Triển khai:** GitHub Pages / Vercel / Netlify.

## Cấu trúc thư mục

- `index.html`: Trang chủ, giới thiệu và danh sách bài viết.
- `profile.html`: Trang giới thiệu bản thân, kỹ năng và định hướng.
- `blog/`: Thư mục chứa các bài viết chi tiết.
    - `java-*.html`: Các bài viết về ngôn ngữ Java.
    - `js-*.html`: Các bài viết về ngôn ngữ JavaScript.

## Nội dung Blog

Blog bao gồm 9 bài viết chuyên sâu về Java và JavaScript, lồng ghép tư duy An ninh mạng - chuyên ngành chính của tác giả Đoàn Minh Quân.

