#!/usr/bin/env python3
from __future__ import annotations

import os
import plistlib
import shutil
import subprocess
from pathlib import Path


WORKFLOW_NAME = "发送到 VibeDrop.workflow"
HOME = Path.home()
WORKFLOW_DIR = HOME / "Library" / "Services" / WORKFLOW_NAME
RESOURCES_DIR = WORKFLOW_DIR / "Contents" / "Resources"
INFO_PLIST_PATH = WORKFLOW_DIR / "Contents" / "Info.plist"
DOCUMENT_PATH = RESOURCES_DIR / "document.wflow"
PBS_BINARY = "/System/Library/CoreServices/pbs"


INFO_PLIST = {
    "CFBundleDevelopmentRegion": "zh_CN",
    "CFBundleIdentifier": "com.vibedrop.finder.sendworkflow",
    "CFBundleName": "发送到 VibeDrop",
    "CFBundleShortVersionString": "1.0",
    "NSServices": [
        {
            "NSMenuItem": {
                "default": "发送到 VibeDrop",
            },
            "NSMessage": "runWorkflowAsService",
            "NSRequiredContext": {
                "NSApplicationIdentifier": "com.apple.finder",
            },
            "NSSendFileTypes": [
                "public.item",
                "public.folder",
            ],
        }
    ],
}


COMMAND_STRING = r"""set -euo pipefail
if [ "$#" -eq 0 ]; then
  exit 0
fi

QUEUE_DIR="$HOME/.vibedrop/finder-share-requests"
mkdir -p "$QUEUE_DIR"
REQUEST_ID="finder-$(date +%s)-$$"
REQUEST_FILE="$QUEUE_DIR/$REQUEST_ID.json"

/usr/bin/python3 - "$REQUEST_FILE" "$@" <<'PY'
import json
import os
import sys

request_file = sys.argv[1]
paths = [os.path.abspath(path) for path in sys.argv[2:] if path]
if not paths:
    raise SystemExit(0)

payload = {
    "paths": paths,
    "source": "finder-service",
}

with open(request_file, "w", encoding="utf-8") as fh:
    json.dump(payload, fh, ensure_ascii=False)
PY

/usr/bin/open -ga "/Applications/VibeDrop.app"
"""


DOCUMENT_WFLOW = {
    "actions": [
        {
            "action": {
                "ActionBundlePath": "/System/Library/Automator/Run Shell Script.action",
                "ActionName": "Run Shell Script",
                "ActionParameters": {
                    "CheckedForUserDefaultShell": 1,
                    "COMMAND_STRING": COMMAND_STRING,
                    "inputMethod": 1,
                    "shell": "/bin/bash",
                    "source": "",
                },
                "AMAccepts": {
                    "Container": "List",
                    "Optional": 0,
                    "Types": ["com.apple.cocoa.path"],
                },
                "AMActionVersion": "2.0.3",
                "AMApplication": ["Automator"],
                "AMParameterProperties": {
                    "CheckedForUserDefaultShell": {},
                    "COMMAND_STRING": {},
                    "inputMethod": {},
                    "shell": {},
                    "source": {},
                },
                "AMProvides": {
                    "Container": "List",
                    "Types": ["com.apple.cocoa.path"],
                },
                "arguments": {
                    "0": {
                        "default value": 1,
                        "name": "inputMethod",
                        "required": "0",
                        "type": "0",
                        "uuid": "0",
                    },
                    "1": {
                        "default value": "",
                        "name": "source",
                        "required": "0",
                        "type": "0",
                        "uuid": "1",
                    },
                    "2": {
                        "default value": 1,
                        "name": "CheckedForUserDefaultShell",
                        "required": "0",
                        "type": "0",
                        "uuid": "2",
                    },
                    "3": {
                        "default value": "",
                        "name": "COMMAND_STRING",
                        "required": "0",
                        "type": "0",
                        "uuid": "3",
                    },
                    "4": {
                        "default value": "/bin/bash",
                        "name": "shell",
                        "required": "0",
                        "type": "0",
                        "uuid": "4",
                    },
                },
                "BundleIdentifier": "com.apple.RunShellScript",
                "CanShowSelectedItemsWhenRun": 1,
                "CanShowWhenRun": 1,
                "Category": ["AMCategoryFilesAndFolders"],
                "CFBundleVersion": "2.0.3",
                "Class Name": "RunShellScriptAction",
                "InputUUID": "F4953657-D23D-44D6-B09E-AD2C2E732001",
                "isViewVisible": 1,
                "Keywords": [
                    "Finder",
                    "Send",
                    "File",
                    "Folder",
                    "VibeDrop",
                ],
                "OutputUUID": "5A48D8D7-4151-4CEC-9E6C-572D1B31A90B",
                "UUID": "2F18A4B2-85D9-42D8-B1C3-7D7C4AA7B001",
            },
            "isViewVisible": 1,
        }
    ],
    "AMApplicationBuild": "510",
    "AMApplicationVersion": "2.10",
    "AMDocumentVersion": "2",
    "connectors": {},
    "state": {
        "AMLogTabViewSelectedIndex": 0,
    },
    "workflowMetaData": {
        "serviceApplicationBundleID": "com.apple.finder",
        "serviceApplicationPath": "/System/Library/CoreServices/Finder.app",
        "serviceInputTypeIdentifier": "com.apple.Automator.fileSystemObject",
        "serviceOutputTypeIdentifier": "com.apple.Automator.nothing",
        "serviceProcessesInput": 0,
        "workflowTypeIdentifier": "com.apple.Automator.servicesMenu",
    },
}


def main() -> None:
    if WORKFLOW_DIR.exists():
        shutil.rmtree(WORKFLOW_DIR)

    RESOURCES_DIR.mkdir(parents=True, exist_ok=True)

    with INFO_PLIST_PATH.open("wb") as fp:
        plistlib.dump(INFO_PLIST, fp, fmt=plistlib.FMT_XML)

    with DOCUMENT_PATH.open("wb") as fp:
        plistlib.dump(DOCUMENT_WFLOW, fp, fmt=plistlib.FMT_XML)

    if Path(PBS_BINARY).exists():
        subprocess.run([PBS_BINARY, "-flush"], check=False)
        subprocess.run([PBS_BINARY, "-update"], check=False)


if __name__ == "__main__":
    main()
