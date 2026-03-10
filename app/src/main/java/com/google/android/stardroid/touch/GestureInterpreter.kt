// Copyright 2010 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.android.stardroid.touch

import android.content.Context
import android.util.Log
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import com.google.android.stardroid.activities.DynamicStarMapActivity
import com.google.android.stardroid.activities.util.FullscreenControlsManager
import com.google.android.stardroid.renderer.SkyRenderer
import com.google.android.stardroid.util.MiscUtil.getTag

/**
 * Processes touch events and scrolls the screen in manual mode.
 *
 * @author John Taylor
 */
class GestureInterpreter(
  private val fullscreenControlsManager: FullscreenControlsManager,
  private val mapMover: MapMover,
  private val renderer: SkyRenderer,
  private val activity:DynamicStarMapActivity
) : SimpleOnGestureListener() {
  private val flinger = Flinger{ distanceX: Float, distanceY: Float ->
    mapMover.onDrag(
      distanceX,
      distanceY
    )
  }


  override fun onDown(e: MotionEvent): Boolean {
    Log.d(TAG, "Tap down")
    flinger.stop()
    return true
  }

  override fun onFling(
    e1: MotionEvent,
    e2: MotionEvent,
    velocityX: Float,
    velocityY: Float
  ): Boolean {
    Log.d(TAG, "Flinging $velocityX, $velocityY")
    flinger.fling(velocityX, velocityY)
    return true
  }

  override fun onSingleTapUp(e: MotionEvent): Boolean {
    Log.d(TAG, "Tap up")
    fullscreenControlsManager.toggleControls()
    return true
  }

  override fun onDoubleTap(e: MotionEvent): Boolean {
    Log.d(TAG, "Double tap")
    if(activity.balayage){
      activity.balayage = false
      activity.getInfoAboutObject(activity.balayageObject)
    }
    else if(activity.inTargetDirection){
      activity.cancelSearch()
      activity.getInfoAboutObject(activity.target)
    }
    else {
      renderer.readCloseObjects()
    }
    return false
  }

  override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
    Log.d(TAG, "Confirmed single tap")
    return false
  }

  override fun onShowPress(e: MotionEvent) {
    super.onShowPress(e)
    Log.d(TAG, "showpress")
  }

  companion object {
    private val TAG = getTag(GestureInterpreter::class.java)
  }
}