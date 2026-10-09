/*
 * rmAgentMa
 * Copyright 2026 rmAgentMa contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.rmagentma.build;

import java.io.IOException;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.LocalState;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(because = "Downloads and validates external release archives in a local cache")
public abstract class PrepareMoshArtifacts extends DefaultTask {
    @Input
    public abstract Property<String> getReleaseTag();

    @LocalState
    public abstract DirectoryProperty getDownloadDirectory();

    @OutputDirectory
    public abstract DirectoryProperty getJniLibsDirectory();

    @OutputDirectory
    public abstract DirectoryProperty getAssetsDirectory();

    @TaskAction
    public void prepare() throws IOException {
        MoshArtifacts.prepare(
                getReleaseTag().get(),
                getDownloadDirectory().get().getAsFile().toPath(),
                getJniLibsDirectory().get().getAsFile().toPath(),
                getAssetsDirectory().get().getAsFile().toPath());
    }
}
