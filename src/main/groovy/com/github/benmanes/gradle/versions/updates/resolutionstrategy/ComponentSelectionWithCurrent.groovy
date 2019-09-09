package com.github.benmanes.gradle.versions.updates.resolutionstrategy

import com.thoughtworks.xstream.mapper.Mapper
import org.gradle.api.artifacts.ComponentMetadata
import org.gradle.api.artifacts.ComponentSelection
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

import javax.annotation.Nullable

class ComponentSelectionWithCurrent implements ComponentSelection {

  ComponentSelectionWithCurrent(String currentVersion, ComponentSelection delegate) {
    this.currentVersion = currentVersion
    this.delegate = delegate
  }

  final String currentVersion
  private final ComponentSelection delegate

  ModuleComponentIdentifier getCandidate() {
    return delegate.candidate
  }

  @Nullable
  ComponentMetadata getMetadata() {
    return delegate.metadata
  }

  def <T> T getDescriptor(Class<T> descriptorClass) {
    return delegate.getDescriptor(descriptorClass)
  }

  void reject(String reason) {
    delegate.reject(reason)
  }


  @Override
  public String toString() {
    final StringBuffer sb = new StringBuffer("ComponentSelectionWithCurrent{");
    sb.append("group=").append(getCandidate().group);
    sb.append(", module=").append(getCandidate().module);
    sb.append(", version=").append(getCandidate().version)
    sb.append(", currentVersion='").append(currentVersion).append('\'');
    sb.append('}');
    return sb.toString();
  }
}
