"use client";

import { useEffect, useState, useCallback } from "react";
import { 
  onAuthStateChanged, 
  signInWithPopup, 
  signInWithRedirect,
  getRedirectResult,
  setPersistence,
  browserLocalPersistence,
  signOut, 
  User 
} from "firebase/auth";
import { 
  doc, 
  onSnapshot, 
  setDoc, 
  updateDoc, 
  Timestamp, 
  serverTimestamp 
} from "firebase/firestore";
import { auth, firestore, googleProvider } from "@/lib/firebase";

export interface UserSubscription {
  uid: string;
  email: string;
  displayName: string;
  createdAtMs: number;
  expiresAtMs: number;
  status: "active" | "expired" | "extended";
  isUnlimited: boolean;
  isAdmin: boolean;
  note: string;
}

const MAX_REWARD_EXTEND_MS = 24 * 60 * 60 * 1000; // 24 hours anti-fraud cap (same as Android)

export function useSubscription() {
  const [user, setUser] = useState<User | null>(null);
  const [subscription, setSubscription] = useState<UserSubscription | null>(null);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [currentTime, setCurrentTime] = useState<number>(Date.now());

  // Keep live second countdown updated
  useEffect(() => {
    const timer = setInterval(() => setCurrentTime(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);

  // Process Google redirect result if returning from redirect sign-in
  useEffect(() => {
    getRedirectResult(auth)
      .then((res) => {
        if (res?.user) {
          setUser(res.user);
        }
      })
      .catch((err) => {
        console.warn("Redirect sign-in check:", err);
      });
  }, []);

  // Listen for Firebase Auth state changes
  useEffect(() => {
    const unsubscribeAuth = onAuthStateChanged(auth, (firebaseUser) => {
      setUser(firebaseUser);
      if (!firebaseUser) {
        setSubscription(null);
        setIsLoading(false);
      }
    });

    return () => unsubscribeAuth();
  }, []);

  // Listen for realtime Firestore changes on users/{uid}
  useEffect(() => {
    if (!user) return;

    setIsLoading(true);
    const userDocRef = doc(firestore, "users", user.uid);

    const unsubscribeDoc = onSnapshot(
      userDocRef,
      async (snapshot) => {
        setIsLoading(false);
        if (snapshot.exists()) {
          const data = snapshot.data();
          const sub: UserSubscription = {
            uid: user.uid,
            email: data.email || user.email || "",
            displayName: data.displayName || user.displayName || "User",
            createdAtMs: data.createdAtMs || Date.now(),
            expiresAtMs: data.expiresAtMs || 0,
            status: data.status || "expired",
            isUnlimited: !!data.isUnlimited,
            isAdmin: !!data.isAdmin,
            note: data.note || "",
          };
          setSubscription(sub);
        } else {
          // Initialize new user (starts expired, must watch ad or subscribe)
          const now = Date.now();
          const initialData = {
            uid: user.uid,
            email: user.email || "",
            displayName: user.displayName || "",
            createdAtMs: now,
            expiresAtMs: now, // Expired by default
            expiresAt: Timestamp.fromMillis(now),
            status: "expired",
            isUnlimited: false,
            isAdmin: false,
            note: "New Web user (Ad-supported: 1 ad = 10 mins)",
            updatedAt: serverTimestamp(),
          };

          await setDoc(userDocRef, initialData, { merge: true });
          setSubscription({
            uid: user.uid,
            email: user.email || "",
            displayName: user.displayName || "",
            createdAtMs: now,
            expiresAtMs: now,
            status: "expired",
            isUnlimited: false,
            isAdmin: false,
            note: initialData.note,
          });
        }
      },
      (error) => {
        console.error("Firestore subscription sync error:", error);
        setIsLoading(false);
      }
    );

    return () => unsubscribeDoc();
  }, [user]);

  // Expiry and time calculations
  const isAdmin = !!subscription?.isAdmin;
  const isUnlimited = !!subscription?.isUnlimited;
  const expiresAtMs = subscription?.expiresAtMs || 0;
  
  const isExpired = !isAdmin && !isUnlimited && (expiresAtMs <= currentTime);
  const remainingMs = Math.max(0, expiresAtMs - currentTime);

  const remainingMinutes = Math.floor(remainingMs / (60 * 1000));
  const remainingSeconds = Math.floor((remainingMs / 1000) % 60);
  const remainingHours = Math.floor(remainingMs / (60 * 60 * 1000));

  const formattedTimeLeft = () => {
    if (isAdmin) return "Admin (Unlimited)";
    if (isUnlimited) return "VIP (Unlimited)";
    if (isExpired) return "Expired (Need Ad or VIP)";
    if (remainingHours > 0) {
      return `${remainingHours}h ${remainingMinutes % 60}m remaining`;
    }
    return `${remainingMinutes}m ${remainingSeconds}s remaining`;
  };

  // Grant Ad Reward (Default: 10 mins per ad, capped at 24 hours max)
  const grantAdRewardMinutes = useCallback(async (minutes: number = 10): Promise<boolean> => {
    if (!user) return false;
    try {
      const now = Date.now();
      const currentExpiry = subscription?.expiresAtMs || 0;
      
      // Prevent stacking beyond 24 hours from now
      if (!isAdmin && (currentExpiry - now) >= MAX_REWARD_EXTEND_MS) {
        return false;
      }

      const baseTime = (!isExpired && currentExpiry > now) ? currentExpiry : now;
      const targetExpiry = baseTime + (minutes * 60 * 1000);
      const maxAllowedExpiry = now + MAX_REWARD_EXTEND_MS;
      const newExpiry = isAdmin ? targetExpiry : Math.min(targetExpiry, maxAllowedExpiry);

      const userDocRef = doc(firestore, "users", user.uid);
      await updateDoc(userDocRef, {
        expiresAtMs: newExpiry,
        expiresAt: Timestamp.fromMillis(newExpiry),
        status: "active",
        updatedAt: serverTimestamp(),
      });

      return true;
    } catch (err) {
      console.error("Failed to grant ad reward:", err);
      return false;
    }
  }, [user, subscription, isAdmin, isExpired]);

  // Auth actions
  const loginWithGoogle = async () => {
    try {
      await setPersistence(auth, browserLocalPersistence);
      const result = await signInWithPopup(auth, googleProvider);
      if (result?.user) {
        setUser(result.user);
      }
    } catch (err: any) {
      console.warn("Popup attempt error, falling back to redirect:", err);
      try {
        await signInWithRedirect(auth, googleProvider);
      } catch (redirectErr) {
        console.error("Google login failed:", redirectErr);
      }
    }
  };

  const logout = async () => {
    try {
      await signOut(auth);
    } catch (err) {
      console.error("Logout failed:", err);
    }
  };

  return {
    user,
    subscription,
    isLoading,
    isExpired,
    isUnlimited: isUnlimited || isAdmin,
    isAdmin,
    formattedTimeLeft: formattedTimeLeft(),
    remainingMinutes,
    grantAdRewardMinutes,
    loginWithGoogle,
    logout,
  };
}
