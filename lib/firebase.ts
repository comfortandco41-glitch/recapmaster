import { initializeApp, getApps, getApp } from "firebase/app";
import { getAuth, GoogleAuthProvider } from "firebase/auth";
import { getFirestore } from "firebase/firestore";

// Firebase credentials for recapmaster-6d1c6 (matching Android app)
const firebaseConfig = {
  apiKey: process.env.NEXT_PUBLIC_FIREBASE_API_KEY || "AIzaSyA5f0k8iE47bPKZBMXcGeuGBEqRc9bz60Q",
  authDomain: "recapmaster-6d1c6.firebaseapp.com",
  projectId: "recapmaster-6d1c6",
  storageBucket: "recapmaster-6d1c6.firebasestorage.app",
  messagingSenderId: "728125206973",
  appId: "1:728125206973:web:recapmaster_web_client",
};

// Singleton pattern to prevent re-initialization in Next.js
const app = getApps().length > 0 ? getApp() : initializeApp(firebaseConfig);
export const auth = getAuth(app);
export const firestore = getFirestore(app);
export const googleProvider = new GoogleAuthProvider();
export default app;
