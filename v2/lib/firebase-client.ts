"use client";
import { getApps, initializeApp } from "firebase/app";
import { getAuth } from "firebase/auth";

export function clientAuth() {
  const apiKey = process.env.NEXT_PUBLIC_FIREBASE_API_KEY;
  if (!apiKey) return null;
  const app = getApps()[0] ?? initializeApp({ apiKey,
    authDomain: process.env.NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN,
    projectId: process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID });
  return getAuth(app);
}
