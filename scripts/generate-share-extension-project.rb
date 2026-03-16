#!/usr/bin/env ruby
# frozen_string_literal: true

require 'fileutils'
require 'pathname'
require 'xcodeproj'

ROOT = Pathname.new(__dir__).join('..').expand_path
EXTENSION_ROOT = ROOT.join('desktop', 'share-extension')
PROJECT_PATH = EXTENSION_ROOT.join('VibeDropShare.xcodeproj')
TARGET_NAME = 'VibeDropShare'
PRODUCT_BUNDLE_IDENTIFIER = 'com.vibedrop.desktop.share'

FileUtils.mkdir_p(EXTENSION_ROOT)
FileUtils.rm_rf(PROJECT_PATH)

project = Xcodeproj::Project.new(PROJECT_PATH.to_s)
project.root_object.attributes['LastSwiftUpdateCheck'] = '2600'
project.root_object.attributes['LastUpgradeCheck'] = '2600'

target = project.new_target(:app_extension, TARGET_NAME, :osx, '10.15')
target.product_reference.name = "#{TARGET_NAME}.appex"

group = project.main_group.find_subpath('VibeDropShare', true)
group.set_source_tree('<group>')
group.path = 'VibeDropShare'

source_ref = group.new_file('ShareViewController.swift')
group.new_file('Info.plist')
group.new_file('VibeDropShare.entitlements')
target.add_file_references([source_ref])

frameworks_group = project.frameworks_group || project.main_group.new_group('Frameworks')
[
  '/System/Library/Frameworks/AppKit.framework',
  '/System/Library/Frameworks/Social.framework'
].each do |framework_path|
  ref = frameworks_group.files.find { |file| file.path == framework_path } || frameworks_group.new_file(framework_path)
  target.frameworks_build_phase.add_file_reference(ref, true)
end

target.build_configurations.each do |config|
  config.build_settings['CODE_SIGN_ENTITLEMENTS'] = 'VibeDropShare/VibeDropShare.entitlements'
  config.build_settings['CODE_SIGN_STYLE'] = 'Manual'
  config.build_settings['GENERATE_INFOPLIST_FILE'] = 'NO'
  config.build_settings['INFOPLIST_FILE'] = 'VibeDropShare/Info.plist'
  config.build_settings['LD_RUNPATH_SEARCH_PATHS'] = '$(inherited) @executable_path/../Frameworks @executable_path/../../Frameworks'
  config.build_settings['MACOSX_DEPLOYMENT_TARGET'] = '10.15'
  config.build_settings['PRODUCT_BUNDLE_IDENTIFIER'] = PRODUCT_BUNDLE_IDENTIFIER
  config.build_settings['PRODUCT_NAME'] = TARGET_NAME
  config.build_settings['SKIP_INSTALL'] = 'NO'
  config.build_settings['SWIFT_EMIT_LOC_STRINGS'] = 'NO'
  config.build_settings['SWIFT_VERSION'] = '5.0'
end

project.save
puts "Generated #{PROJECT_PATH}"
