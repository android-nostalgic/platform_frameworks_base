package com.android.internal.view;

import com.android.internal.view.IInputContext;

import android.os.Bundle;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

public class InputConnectionWrapper implements InputConnection {
    private static final int MAX_WAIT_TIME_MILLIS = 2000;
    private final IInputContext mIInputContext;
    
    static class InputContextCallback extends IInputContextCallback.Stub {
        private static final String TAG = "InputConnectionWrapper.ICC";
        public int mSeq;
        public boolean mHaveValue;
        public CharSequence mTextBeforeCursor;
        public CharSequence mTextAfterCursor;
        public ExtractedText mExtractedText;
        public int mCursorCapsMode;
        
        // A 'pool' of one InputContextCallback.  Each ICW request will attempt to gain
        // exclusive access to this object.
        private static InputContextCallback sInstance = new InputContextCallback();
        private static int sSequenceNumber = 1;
        
        /**
         * Returns an InputContextCallback object that is guaranteed not to be in use by
         * any other thread.  The returned object's 'have value' flag is cleared and its expected
         * sequence number is set to a new integer.  We use a sequence number so that replies that
         * occur after a timeout has expired are not interpreted as replies to a later request.
         */
        private static InputContextCallback getInstance() {
            synchronized (InputContextCallback.class) {
                // Return sInstance if it's non-null, otherwise construct a new callback
                InputContextCallback callback;
                if (sInstance != null) {
                    callback = sInstance;
                    sInstance = null;
                    
                    // Reset the callback
                    callback.mHaveValue = false;
                } else {
                    callback = new InputContextCallback();
                }
                
                // Set the sequence number
                callback.mSeq = sSequenceNumber++;
                return callback;
            }
        }
        
        /**
         * Makes the given InputContextCallback available for use in the future.
         */
        private void dispose() {
            synchronized (InputContextCallback.class) {
                // If sInstance is non-null, just let this object be garbage-collected
                if (sInstance == null) {
                    // Allow any objects being held to be gc'ed
                    mTextAfterCursor = null;
                    mTextBeforeCursor = null;
                    mExtractedText = null;
                    sInstance = this;
                }
            }
        }
        
        public void setTextBeforeCursor(CharSequence textBeforeCursor, int seq) {
            synchronized (this) {
                if (seq == mSeq) {
                    mTextBeforeCursor = textBeforeCursor;
                    mHaveValue = true;
                    notifyAll();
                } else {
                    Log.i(TAG, "Got out-of-sequence callback " + seq + " (expected " + mSeq
                            + ") in setTextBeforeCursor, ignoring.");
                }
            }
        }

        public void setTextAfterCursor(CharSequence textAfterCursor, int seq) {
            synchronized (this) {
                if (seq == mSeq) {
                    mTextAfterCursor = textAfterCursor;
                    mHaveValue = true;
                    notifyAll();
                } else {
                    Log.i(TAG, "Got out-of-sequence callback " + seq + " (expected " + mSeq
                            + ") in setTextAfterCursor, ignoring.");
                }
            }
        }

        public void setCursorCapsMode(int capsMode, int seq) {
            synchronized (this) {
                if (seq == mSeq) {
                    mCursorCapsMode = capsMode; 
                    mHaveValue = true;  
                    notifyAll();
                } else {
                    Log.i(TAG, "Got out-of-sequence callback " + seq + " (expected " + mSeq
                            + ") in setCursorCapsMode, ignoring.");
                }
            }
        }

        public void setExtractedText(ExtractedText extractedText, int seq) {
            synchronized (this) {
                if (seq == mSeq) {
                    mExtractedText = extractedText;
                    mHaveValue = true;
                    notifyAll();
                } else {
                    Log.i(TAG, "Got out-of-sequence callback " + seq + " (expected " + mSeq
                            + ") in setExtractedText, ignoring.");
                }
            }
        }
        
        /**
         * Waits for a result for up to {@link #MAX_WAIT_TIME_MILLIS} milliseconds.
         * 
         * <p>The caller must be synchronized on this callback object.
         */
        void waitForResultLocked() {
            long startTime = SystemClock.uptimeMillis();
            long endTime = startTime + MAX_WAIT_TIME_MILLIS;

            while (!mHaveValue) {
                long remainingTime = endTime - SystemClock.uptimeMillis();
                if (remainingTime <= 0) {
                    Log.w(TAG, "Timed out waiting on IInputContextCallback");
                    return;
                }
                try {
                    wait(remainingTime);
                } catch (InterruptedException e) {
                }
            }
        }
    }

    public InputConnectionWrapper(IInputContext inputContext) {
        mIInputContext = inputContext;
    }

    public CharSequence getTextAfterCursor(int length, int flags) {
        CharSequence value = null;
        try {
            InputContextCallback callback = InputContextCallback.getInstance();
            mIInputContext.getTextAfterCursor(length, flags, callback.mSeq, callback);
            synchronized (callback) {
                callback.waitForResultLocked();
                if (callback.mHaveValue) {
                    value = callback.mTextAfterCursor;
                }
            }
            callback.dispose();
        } catch (RemoteException e) {
            return null;
        }
        return value;
    }
    
    public CharSequence getTextBeforeCursor(int length, int flags) {
        CharSequence value = null;
        try {
            InputContextCallback callback = InputContextCallback.getInstance();
            mIInputContext.getTextBeforeCursor(length, flags, callback.mSeq, callback);
            synchronized (callback) {
                callback.waitForResultLocked();
                if (callback.mHaveValue) {
                    value = callback.mTextBeforeCursor;
                }
            }
            callback.dispose();
        } catch (RemoteException e) {
            return null;
        }
        return value;
    }
    
    public int getCursorCapsMode(int reqModes) {
        int value = 0;
        try {
            InputContextCallback callback = InputContextCallback.getInstance();
            mIInputContext.getCursorCapsMode(reqModes, callback.mSeq, callback);
            synchronized (callback) {
                callback.waitForResultLocked();
                if (callback.mHaveValue) {
                    value = callback.mCursorCapsMode;
                }
            }
            callback.dispose();
        } catch (RemoteException e) {
            return 0;
        }
        return value;
    }
    
    public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
        ExtractedText value = null;
        try {
            InputContextCallback callback = InputContextCallback.getInstance();
            mIInputContext.getExtractedText(request, flags, callback.mSeq, callback);
            synchronized (callback) {
                callback.waitForResultLocked();
                if (callback.mHaveValue) {
                    value = callback.mExtractedText;
                }
            }
            callback.dispose();
        } catch (RemoteException e) {
            return null;
        }
        return value;
    }
    
    public boolean commitText(CharSequence text, int newCursorPosition) {
        try {
            mIInputContext.commitText(text, newCursorPosition);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    public boolean commitCompletion(CompletionInfo text) {
        try {
            mIInputContext.commitCompletion(text);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    public boolean setSelection(int start, int end) {
        try {
            mIInputContext.setSelection(start, end);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }
    
    public boolean performContextMenuAction(int id) {
        try {
            mIInputContext.performContextMenuAction(id);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }
    
    public boolean setComposingText(CharSequence text, int newCursorPosition) {
        try {
            mIInputContext.setComposingText(text, newCursorPosition);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    public boolean finishComposingText() {
        try {
            mIInputContext.finishComposingText();
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    public boolean beginBatchEdit() {
        try {
            mIInputContext.beginBatchEdit();
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }
    
    public boolean endBatchEdit() {
        try {
            mIInputContext.endBatchEdit();
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }
    
    public boolean sendKeyEvent(KeyEvent event) {
        try {
            mIInputContext.sendKeyEvent(event);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    public boolean clearMetaKeyStates(int states) {
        try {
            mIInputContext.clearMetaKeyStates(states);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }
    
    public boolean deleteSurroundingText(int leftLength, int rightLength) {
        try {
            mIInputContext.deleteSurroundingText(leftLength, rightLength);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    public boolean hideStatusIcon() {
        try {
            mIInputContext.showStatusIcon(null, 0);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    public boolean showStatusIcon(String packageName, int resId) {
        try {
            mIInputContext.showStatusIcon(packageName, resId);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    public boolean performPrivateCommand(String action, Bundle data) {
        try {
            mIInputContext.performPrivateCommand(action, data);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }
}