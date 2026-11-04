import type { Metadata } from "next";
import "./globals.css";
export const metadata: Metadata = { title: "Noka Control · Roblox Cloud", description: "Quản lý các bản Noka trên Redfinger từ xa." };
export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="vi"><body>{children}</body></html>;
}
