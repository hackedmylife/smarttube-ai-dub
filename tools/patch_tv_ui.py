#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()

def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, got {count}")
    return text.replace(old, new, 1)

# VideoPlayerGlue: register the action and show it in the secondary control row.
glue_path = root / "smarttubetv/src/main/java/com/liskovsoft/smartyoutubetv2/tv/ui/playback/other/VideoPlayerGlue.java"
glue = glue_path.read_text(encoding="utf-8")

if "new AiDubAction(context)" not in glue:
    glue = replace_once(
        glue,
        "import com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions.AFRAction;",
        "import com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions.AFRAction;\n"
        "import com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions.AiDubAction;",
        "VideoPlayerGlue import")

    glue = replace_once(
        glue,
        "        putAction(new SoundOffAction(context));",
        "        putAction(new SoundOffAction(context));\n"
        "        putAction(new AiDubAction(context));",
        "VideoPlayerGlue constructor")

    glue = replace_once(
        glue,
        "        super.onCreateSecondaryActions(adapter);\n",
        "        super.onCreateSecondaryActions(adapter);\n\n"
        "        adapter.add(mActions.get(AiDubAction.ACTION_ID));\n",
        "VideoPlayerGlue secondary actions")

glue_path.write_text(glue, encoding="utf-8")

# PlaybackFragment: own AI Dub lifecycle and intercept the native action.
fragment_path = root / "smarttubetv/src/main/java/com/liskovsoft/smartyoutubetv2/tv/ui/playback/PlaybackFragment.java"
fragment = fragment_path.read_text(encoding="utf-8")

if "mAiDubUiController" not in fragment:
    fragment = replace_once(
        fragment,
        "import com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions.AFRAction;" if "import com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions.AFRAction;" in fragment else
        "import com.liskovsoft.smartyoutubetv2.tv.ui.playback.mod.SeekModePlaybackFragment;",
        ("import com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions.AiDubAction;\n"
         "import com.liskovsoft.smartyoutubetv2.tv.ui.playback.aidub.AiDubUiController;\n"
         "import com.liskovsoft.smartyoutubetv2.tv.ui.playback.mod.SeekModePlaybackFragment;"),
        "PlaybackFragment imports")

    fragment = replace_once(
        fragment,
        "    private String mSelectedVideoId;\n",
        "    private String mSelectedVideoId;\n"
        "    private AiDubUiController mAiDubUiController;\n",
        "PlaybackFragment field")

    fragment = replace_once(
        fragment,
        "    private void destroyPlayerObjects() {\n",
        "    private void destroyPlayerObjects() {\n"
        "        if (mAiDubUiController != null) {\n"
        "            mAiDubUiController.release();\n"
        "            mAiDubUiController = null;\n"
        "        }\n",
        "PlaybackFragment destroy")

    fragment = replace_once(
        fragment,
        "        mExoPlayerController.setPlayerView(mPlayerGlue);\n",
        "        mExoPlayerController.setPlayerView(mPlayerGlue);\n"
        "        mAiDubUiController = new AiDubUiController(getContext(), mPlayer, mPlayerGlue);\n",
        "PlaybackFragment create glue")

    fragment = replace_once(
        fragment,
        "        public void onAction(int actionId, int actionIndex) {\n"
        "            mPlaybackPresenter.onButtonClicked(actionId, actionIndex);\n"
        "        }",
        "        public void onAction(int actionId, int actionIndex) {\n"
        "            if (mAiDubUiController != null && mAiDubUiController.handles(actionId)) {\n"
        "                mAiDubUiController.onAction();\n"
        "                return;\n"
        "            }\n"
        "            mPlaybackPresenter.onButtonClicked(actionId, actionIndex);\n"
        "        }",
        "PlaybackFragment action")

fragment_path.write_text(fragment, encoding="utf-8")
print("AI Dub TV UI patch applied")
