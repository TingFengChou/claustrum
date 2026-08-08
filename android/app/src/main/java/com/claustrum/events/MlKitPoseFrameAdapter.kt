package com.claustrum.events

import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark

/** Keep all ML Kit types at this adapter boundary; downstream L2 data stays pixel-free. */
internal fun Pose.toPoseFrame(
    atMs: Long,
    uprightWidth: Int,
    uprightHeight: Int,
): PoseFrame {
    require(uprightWidth > 0 && uprightHeight > 0) { "upright frame dimensions must be positive" }
    val joints = buildMap {
        putLandmark(this@toPoseFrame, PoseJoint.LEFT_SHOULDER, PoseLandmark.LEFT_SHOULDER, uprightWidth, uprightHeight)
        putLandmark(this@toPoseFrame, PoseJoint.RIGHT_SHOULDER, PoseLandmark.RIGHT_SHOULDER, uprightWidth, uprightHeight)
        putLandmark(this@toPoseFrame, PoseJoint.LEFT_HIP, PoseLandmark.LEFT_HIP, uprightWidth, uprightHeight)
        putLandmark(this@toPoseFrame, PoseJoint.RIGHT_HIP, PoseLandmark.RIGHT_HIP, uprightWidth, uprightHeight)
        putLandmark(this@toPoseFrame, PoseJoint.LEFT_KNEE, PoseLandmark.LEFT_KNEE, uprightWidth, uprightHeight)
        putLandmark(this@toPoseFrame, PoseJoint.RIGHT_KNEE, PoseLandmark.RIGHT_KNEE, uprightWidth, uprightHeight)
        putLandmark(this@toPoseFrame, PoseJoint.LEFT_ANKLE, PoseLandmark.LEFT_ANKLE, uprightWidth, uprightHeight)
        putLandmark(this@toPoseFrame, PoseJoint.RIGHT_ANKLE, PoseLandmark.RIGHT_ANKLE, uprightWidth, uprightHeight)
    }
    return PoseFrame(atMs = atMs, points = joints)
}

private fun MutableMap<PoseJoint, PosePoint>.putLandmark(
    pose: Pose,
    joint: PoseJoint,
    landmarkType: Int,
    width: Int,
    height: Int,
) {
    val landmark = pose.getPoseLandmark(landmarkType) ?: return
    this[joint] = PosePoint(
        x = landmark.position.x / width,
        y = landmark.position.y / height,
        likelihood = landmark.inFrameLikelihood,
    )
}
