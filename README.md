# Trợ lý cờ vua (Android)

App Android phân tích thế cờ và gợi ý nước đi tốt nhất: quân cần đi **nhấp nháy** và có **mũi tên xanh** chỉ vào ô cần đến.

## Tính năng

- Bàn cờ trong app: chạm quân rồi chạm ô đến để đi (có kiểm tra nước đi hợp lệ, nhập thành, bắt tốt qua đường, phong cấp).
- Mở app ở thế nào cũng phân tích được: thế ban đầu, hoặc thế đã đi dở (dùng **Xếp quân**, **FEN** hoặc **Ảnh bàn cờ**).
- Chọn bên mình cầm: **Tự động** (gợi ý cho bên đang đến lượt), **Trắng**, **Đen** (bàn cờ tự lật theo bên bạn cầm).
- Mức phân tích: Nhanh / Vừa / Mạnh.
- Hoàn tác, ván mới, lật bàn, sao chép/dán FEN.
- Nhập thế cờ từ ảnh chụp **bàn cờ thật**: kéo 4 góc, app tự nhận ô nào có quân, quân màu gì, bạn cầm bên nào.

## Giới hạn cần biết

- Engine cờ viết sẵn trong app (alpha-beta, chạy offline), không phải Stockfish. Mạnh ở mức người chơi phong trào khá, không ngang Stockfish.
- Nhận diện ảnh không dùng AI nhận dạng quân. App nhận ra ô có quân và màu quân, nhưng **loại quân** chỉ biết khi quân còn ở ô xuất phát. Quân khác sẽ hiện dấu `?`, bạn chạm chọn lại loại quân trước khi phân tích. Chụp từ trên xuống, đủ sáng, bàn cờ phẳng thì kết quả tốt hơn.
- App **không** đọc màn hình của app cờ khác.

## Lấy file APK bằng GitHub (không cần cài Android Studio)

1. Giải nén file zip này.
2. Tạo repository mới trên github.com (ví dụ `ChessHelper`).
3. Đưa toàn bộ nội dung thư mục lên repo (nhớ cả thư mục ẩn `.github`):
   - Cách dễ: vào repo, chọn **Add file → Upload files**, kéo thả tất cả file và thư mục vào, bấm **Commit changes**.
   - Hoặc dùng lệnh:
     ```
     git init
     git add .
     git commit -m "first commit"
     git branch -M main
     git remote add origin https://github.com/<tên-bạn>/ChessHelper.git
     git push -u origin main
     ```
4. Vào tab **Actions**, workflow **Build APK** sẽ tự chạy (khoảng 3-6 phút).
5. Tải APK:
   - Mục **Releases** của repo: tải `ChessHelper.apk`, hoặc
   - Trong lần chạy ở tab **Actions**, kéo xuống **Artifacts**, tải `ChessHelper-apk`.
6. Chép APK vào điện thoại và mở để cài (cho phép "cài ứng dụng từ nguồn không xác định" nếu máy hỏi).

Nếu build lỗi, mở lần chạy bị đỏ trong tab Actions và gửi lại nội dung lỗi để sửa.
