# Tohru

[English](README.md) · [Tiếng Việt](README.vi.md)

Tohru là trình quản lý nhiệm vụ cho Android. Mỗi nhiệm vụ là một tệp Markdown
trong kho Git do bạn sở hữu. Ứng dụng không có tài khoản, không có máy chủ và
không dùng dịch vụ đồng bộ riêng.

[![CI](https://github.com/TohruTodo/Tohru-android/actions/workflows/ci.yml/badge.svg)](https://github.com/TohruTodo/Tohru-android/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/TohruTodo/Tohru-android)](https://github.com/TohruTodo/Tohru-android/releases)
[![License](https://img.shields.io/badge/license-GPL--3.0--or--later-blue.svg)](LICENSE)

## Cài đặt

Tải APK mới nhất từ [Releases](https://github.com/TohruTodo/Tohru-android/releases).
Tohru cần Android 8.0 (API 26) trở lên.

Lần chạy đầu tiên tạo một kho trong bộ nhớ riêng của ứng dụng. Để dùng kho có
sẵn, đặt đường dẫn trong Cài đặt. Android 11 trở lên có thể cần quyền truy cập
tất cả tệp cho đường dẫn nằm ngoài vùng nhớ của ứng dụng.

## Tệp nhiệm vụ

Mỗi nhiệm vụ nằm tại `tasks/[<trạng thái>/][<nhóm>/][<chủ đề>/]<id>_<slug>.md`.
Thư mục trạng thái đứng trước, rồi đến thư mục nhóm và chủ đề, cuối cùng là tệp.

```text
tasks/
├── README.md                    # ghi chú tuỳ chọn cho thư mục
├── _order.json                  # thứ tự thủ công của các mục cùng cấp
├── work/
│   └── reports/
│       └── 1732000000000-0001_work_reports_weekly_report.md
├── _done/
│   └── work/
│       └── 1732000000000-0002_work_review_pr.md
└── _archived/
    └── _done/
        └── 1732000000000-0003_old_task.md
```

Mỗi tệp bắt đầu bằng khối frontmatter:

```markdown
---
id: 1732000000000-0001
title: Work(reports): Weekly report
created: 1732000000000
updated: 1732000000000
---

Nội dung Markdown.
```

- `id`, `title`, `created` và `updated` là bắt buộc. Thời gian tính bằng
  mili giây Unix epoch.
- Tiêu đề dạng `nhóm(chủ đề): nội dung` quyết định đường dẫn thư mục. Tiêu đề
  thường không thuộc nhóm nào. Dùng `_` thay cho dấu cách trong tên nhóm.
- Thư mục quyết định trạng thái. Nhiệm vụ đang mở không có thư mục trạng thái,
  nhiệm vụ đã xong dùng `_done`, nhiệm vụ đã lưu trữ dùng `_archived`, và nhiệm
  vụ đã xong rồi lưu trữ dùng `_archived/_done`.
- Tên thư mục và slug tệp theo cùng một quy tắc: Unicode NFC, chữ thường, giữ
  chữ cái, dấu kết hợp và chữ số Unicode, mỗi cụm ký tự khác thay bằng `_`,
  slug tối đa 48 ký tự.
- Tệp nằm ngoài đường dẫn ứng với tiêu đề sẽ bị bỏ qua. Tohru không di chuyển
  tệp đó.
- Liên kết nhiệm vụ là liên kết Markdown tương đối tới tệp đích. Khi đổi tên
  hoặc đổi trạng thái, các liên kết trỏ tới tệp được cập nhật.

## Tính năng

### Bảng và điều hướng

- **Quản lý** hiển thị nhiệm vụ dưới dạng lưới theo bảng chữ cái. README
  trong từng thư mục cũng nằm trong trang này.
- **Nhiệm vụ** hiển thị cây nhóm, chủ đề và nhiệm vụ chưa phân nhóm. Mục **Đã
  xong** thu gọn được và nằm cuối cây.
- Tìm kiếm khớp nhóm, chủ đề, tiêu đề và nội dung. Bộ lọc nhóm cho phép chọn
  nhiều mục.
- Chuyển bảng bằng thao tác vuốt hoặc thanh dưới.

### Chỉnh sửa

- Chạm vào thẻ để sửa tiêu đề và mô tả trong một sheet.
- Vuốt thẻ để hiện nút sửa. Vuốt hết cỡ sẽ mở nhiệm vụ.
- Nhấn giữ thẻ hoặc tiêu đề nhóm để bắt đầu chọn.
- Màn hình chi tiết mở ở chế độ sửa và vuốt sang chế độ đọc. Hai bên dùng chung
  bố cục phần đầu và nội dung.
- Nút mục lục vẽ tiêu đề và các đề mục thành cây.
- Bản nháp được lưu khi rời màn hình hoặc đưa ứng dụng xuống nền.
- Chế độ chọn hỗ trợ đổi trạng thái xong, sao chép, lưu trữ và kéo đổi thứ
  tự. Sao chép tạo danh sách kiểm Markdown. Màn hình Kho lưu trữ khôi phục hoặc
  xoá các mục đã chọn.
- Kéo chỉ đổi thứ tự các mục cùng cấp. Kết quả lưu trong `_order.json` và được
  ưu tiên hơn thứ tự tự động.

### Markdown

- Chế độ đọc hiển thị Markdown chuẩn: đề mục, danh sách, trích dẫn, khối mã và
  danh sách kiểm cục bộ.
- Đánh dấu một mục trong danh sách kiểm sẽ ghi lại nội dung đã sửa. Trạng thái
  xong của nhiệm vụ không đổi.
- Trình sửa có bộ chọn nhiệm vụ để chèn tiêu đề và liên kết tương đối tại vị trí
  con trỏ.
- Liên kết trỏ tới tệp không tồn tại hiển thị là `file not found` và giữ nguyên
  đích liên kết.

### Kho lưu trữ

- Lưu trữ chuyển nhiệm vụ vào thư mục `_archived`. Có thể hoàn tác ngay sau
  thao tác.
- Màn hình Kho lưu trữ cho khôi phục hoặc xoá vĩnh viễn một nhóm mục đã chọn.
- Xoá vĩnh viễn cần xác nhận.

### Đồng bộ Git

- Mỗi lần lưu trong `tasks/` tạo một commit local. Tohru không tự fetch, pull
  hay push ở chế độ nền.
- Bảng Git hiển thị branch, số commit chưa đẩy/chưa kéo, thay đổi local, commit
  gần nhất và lần đồng bộ cuối.
- Commit, Fetch, Pull, Push và Đồng bộ là thao tác tường minh. Đồng bộ commit
  thay đổi còn chờ, pull với rebase rồi push.
- Pull và Push bị tắt khi cây làm việc còn thay đổi chưa commit.
- Màn hình lịch sử là trình xem commit chỉ đọc, có đồ thị, ref, tìm kiếm, phạm vi
  branch, chi tiết commit và diff văn bản.
- Có thể tạo, chuyển và xoá branch. Branch local cũ được đánh dấu.

### Xung đột

- Xung đột mở trình sửa toàn màn hình với diff theo dòng, chọn bản local hoặc
  remote cho từng tệp, và trình sửa thô cho kết quả trộn.
- Hai bên được lưu dưới `.sync-conflict/`. Mười phiên mới nhất được giữ và thư
  mục này không bao giờ đồng bộ.
- Huỷ xung đột giữ nguyên các tệp local.

### Nhập và xuất

- Xuất tạo tệp ZIP chứa ảnh chụp `tasks/`, gồm cả `_order.json`, rồi mở bảng
  chia sẻ của hệ thống.
- Nhập chấp nhận cùng bố cục. Với id trùng, chọn Bỏ qua, Ghi đè hoặc Tạo bản
  sao.
- Tệp ZIP không chứa `.git` hay thông tin đăng nhập.

### Lớp phủ nhiệm vụ nhanh

- Ô cài đặt nhanh **Nhiệm vụ nhanh** mở bảng nhiệm vụ toàn màn hình trên ứng
  dụng khác.
- Lớp phủ cần quyền **Hiển thị trên ứng dụng khác** và hiện thông báo dịch vụ
  chạy nền.
- Bảng này dùng lại cây nhiệm vụ và chia sẻ trạng thái thu gọn với ứng dụng
  chính.
- Mặc định lớp phủ đóng khi tác vụ của ứng dụng đóng. Cài đặt có thể giữ lớp phủ
  chạy sau khi ứng dụng rời khỏi danh sách gần đây.

### Giao diện và ngôn ngữ

- Màu Material You động trên Android 12 trở lên, có bảng màu dự phòng lấy từ
  biểu tượng.
- Chế độ giao diện: hệ thống, sáng, tối và đen.
- Ngôn ngữ: hệ thống, tiếng Việt hoặc tiếng Anh.
- Có thể tắt phản hồi rung trong Cài đặt.

### Gỡ lỗi

- Cài đặt → Gỡ lỗi xuất tệp văn bản chứa thông tin thiết bị và ứng dụng, lần đọc
  logcat gần nhất và các vết lỗi đã lưu.

## Thông tin đăng nhập

- Remote HTTPS dùng token. Remote SSH dùng khoá riêng và mật khẩu khoá tuỳ chọn.
- Token và mật khẩu khoá được mã hoá bằng khoá AES dựa trên Keystore.
- Khoá riêng SSH nằm trong bộ nhớ riêng của ứng dụng.
- Khoá máy chủ SSH được xác nhận ở lần dùng đầu.
- Có thể đặt tên và email người commit. Giá trị mặc định là `Tohru` và
  `tohru@localhost`.

## Bảo trì

- Ref khôi phục nằm dưới `refs/tohru/recovery/`. Cài đặt quyết định số ref được
  giữ.
- Git GC tự động chạy theo chu kỳ đã cấu hình.
- Các tệp ZIP xuất cũ được dọn bớt. Cài đặt quyết định số tệp được giữ.

## Quyền

| Quyền | Mục đích |
| --- | --- |
| `INTERNET` | Fetch, pull và push Git |
| `SYSTEM_ALERT_WINDOW` | Lớp phủ nhiệm vụ nhanh |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Giữ dịch vụ lớp phủ hoạt động |
| `POST_NOTIFICATIONS` | Thông báo dịch vụ chạy nền cho lớp phủ |
| `VIBRATE` | Phản hồi rung |
| `MANAGE_EXTERNAL_STORAGE` | Đường dẫn kho tuỳ chọn nằm ngoài vùng nhớ ứng dụng |

## Build

Yêu cầu: JDK 21 và Android SDK có platform 37.

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

Build offline hoạt động khi các phụ thuộc đã được lưu trong bộ nhớ đệm.

Build release dùng `keystore.properties` khi tệp tồn tại. Tệp này không bao giờ
được commit:

```properties
storeFile=/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

CI đọc `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` và `KEY_PASSWORD` từ
secret trong kho mã nguồn.

Thông tin phiên bản lấy từ Git. `versionName` theo tag gần nhất và `versionCode`
theo số commit. Workflow release chạy khi push tag `v*` và xuất bản APK kèm
`SHA256SUMS`.

## Đóng góp

Mở issue cho lỗi và đề xuất. Pull request cần giữ cấu trúc hiện có và vượt qua
`testDebugUnitTest` cùng `lintDebug`.

## Giấy phép

GPL-3.0-or-later. Xem [LICENSE](LICENSE).
