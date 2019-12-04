// Copyright 2019 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.firebase.gradle.plugins.apiinfo;

import com.android.build.gradle.api.AndroidSourceSet;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

public abstract class GenerateApiTxtFileTask extends DefaultTask {

  @Input
  abstract String getMetalavaJarPath();

  @OutputFile
  abstract File getApiTxt();

  abstract AndroidSourceSet getSourceSet();

  @OutputFile
  abstract File getBaselineFile();

  @Input
  abstract boolean getUpdateBaseline();

  public abstract void setSourceSet(AndroidSourceSet value);

  public abstract void setBaselineFile(File value);

  public abstract void setUpdateBaseline(boolean value);

  public abstract void setMetalavaJarPath(String value);

  public abstract void setApiTxt(File value);

  @TaskAction
  void execute() {
    String sourcePath =
        getSourceSet().getJava().getSrcDirs().stream()
            .filter(File::exists)
            .map(File::getAbsolutePath)
            .collect(Collectors.joining(":"));
    List<String> args =
        new ArrayList<>(
            Arrays.asList(
                getMetalavaJarPath(),
                "--source-path",
                sourcePath,
                "--api",
                getApiTxt().getAbsolutePath(),
                "--format=v2",
                "--delete-empty-baselines"));

    if (getUpdateBaseline()) {
      args.addAll(Arrays.asList("--update-baseline", getBaselineFile().getAbsolutePath()));
    } else if (getBaselineFile().exists()) {
      args.addAll(Arrays.asList("--baseline", getBaselineFile().getAbsolutePath()));
    }

    getProject()
        .javaexec(
            spec -> {
              spec.setMain("-jar");
              spec.setArgs(args);
              spec.setIgnoreExitValue(true);
            });
  }
}
