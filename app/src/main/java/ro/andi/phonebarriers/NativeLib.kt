package ro.andi.phonebarriers

import ro.andi.phonebarriers.data.MotionPoint

object NativeLib {
    init {
        System.loadLibrary("native-lib")
    }

    external fun stringFromJNI(): String

    external fun dtwClassifyAndFindMedoidsForPathsAndAnchors(points: Array<MotionPoint>): String

    external fun matchPathWithBarrierMedoids(last30Points: Array<MotionPoint>, medoids: Array<ro.andi.phonebarriers.data.MedoidPoint>): Boolean
}
