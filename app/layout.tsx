import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Cloud Rejoin Control · Redfinger",
  description: "Quản lý clone, chia lưới Freeform và Auto-rejoin đa lớp trên Redfinger từ xa.",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="vi">
      <body>{children}</body>
    </html>
  );
}
