use std::fs::{rename, File};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use lofty::config::WriteOptions;
use lofty::picture::{MimeType, Picture, PictureType};
use lofty::prelude::{Accessor, TagExt};
use lofty::tag::{Tag, TagType};
use ncmdump::{NcmInfo, Ncmdump};

const UNKNOWN_EXTENSION: &str = "dump";
const BUFFER_SIZE: usize = 8192;

#[no_mangle]
pub extern "system" fn Java_moe_tnxg_ncmdumplsposed_nativebridge_NativeNcmdump_decryptToSibling(
    mut env: JNIEnv,
    _class: JClass,
    input_path: JString,
) -> jstring {
    match read_java_string(&mut env, input_path)
        .and_then(|path| decrypt_to_sibling(Path::new(&path)))
    {
        Ok(output_path) => match env.new_string(output_path.to_string_lossy()) {
            Ok(value) => value.into_raw(),
            Err(error) => {
                throw_io_exception(&mut env, &format!("failed to create Java string: {error}"));
                std::ptr::null_mut()
            }
        },
        Err(error) => {
            throw_io_exception(&mut env, &error);
            std::ptr::null_mut()
        }
    }
}

fn read_java_string(env: &mut JNIEnv, value: JString) -> Result<String, String> {
    env.get_string(&value)
        .map(|value| value.to_string_lossy().into_owned())
        .map_err(|error| format!("failed to read Java string: {error}"))
}

fn decrypt_to_sibling(input_path: &Path) -> Result<PathBuf, String> {
    if !is_ncm_file(input_path)? {
        return Err("input file is not a valid NCM file".to_string());
    }

    let input = File::open(input_path).map_err(|error| format!("failed to open input: {error}"))?;
    let mut dump =
        Ncmdump::from_reader(input).map_err(|error| format!("failed to parse NCM: {error}"))?;
    let metadata = dump.get_info().ok();
    let image = dump.get_image().unwrap_or_default();
    dump.seek(SeekFrom::Start(0))
        .map_err(|error| format!("failed to reset NCM reader: {error}"))?;

    let temporary_path = temporary_output_path(input_path);
    let mut output = File::create(&temporary_path)
        .map_err(|error| format!("failed to create temp output: {error}"))?;
    let mut buffer = [0u8; BUFFER_SIZE];
    loop {
        let size = dump
            .read(&mut buffer)
            .map_err(|error| format!("failed to decrypt NCM: {error}"))?;
        if size == 0 {
            break;
        }
        output
            .write_all(&buffer[..size])
            .map_err(|error| format!("failed to write output: {error}"))?;
    }
    drop(output);

    let extension = detect_extension(&temporary_path)?;
    let final_path = final_output_path(input_path, extension);
    if final_path.exists() {
        std::fs::remove_file(&final_path)
            .map_err(|error| format!("failed to remove existing output: {error}"))?;
    }
    rename(&temporary_path, &final_path)
        .map_err(|error| format!("failed to move output: {error}"))?;
    if let Some(info) = metadata {
        let _ = write_metadata(&final_path, extension, &info, &image);
    }
    Ok(final_path)
}

fn write_metadata(
    output_path: &Path,
    extension: &str,
    info: &NcmInfo,
    image: &[u8],
) -> Result<(), String> {
    let tag_type = match extension {
        "flac" => TagType::VorbisComments,
        "mp3" => TagType::Id3v2,
        _ => return Ok(()),
    };

    let mut tag = Tag::new(tag_type);
    let artist = info
        .artist
        .iter()
        .map(|(name, _)| name.as_str())
        .collect::<Vec<_>>()
        .join("/");
    tag.set_title(info.name.clone());
    tag.set_album(info.album.clone());
    tag.set_artist(artist);

    if let Some(picture) = create_picture(image) {
        tag.push_picture(picture);
    }

    tag.save_to_path(output_path, WriteOptions::default())
        .map_err(|error| format!("failed to write metadata: {error}"))
}

fn create_picture(image: &[u8]) -> Option<Picture> {
    if image.is_empty() {
        return None;
    }

    let mime_type = match image {
        [0xff, 0xd8, 0xff, ..] => MimeType::Jpeg,
        [0x89, 0x50, 0x4e, 0x47, ..] => MimeType::Png,
        _ => return None,
    };

    Some(
        Picture::unchecked(image.to_vec())
            .pic_type(PictureType::CoverFront)
            .mime_type(mime_type)
            .build(),
    )
}

fn is_ncm_file(path: &Path) -> Result<bool, String> {
    let mut file =
        File::open(path).map_err(|error| format!("failed to open candidate: {error}"))?;
    let mut header = [0u8; 8];
    match file.read_exact(&mut header) {
        Ok(()) => Ok(header == *b"CTENFDAM"),
        Err(_) => Ok(false),
    }
}

fn detect_extension(path: &Path) -> Result<&'static str, String> {
    let mut file =
        File::open(path).map_err(|error| format!("failed to inspect output: {error}"))?;
    let mut header = [0u8; 4];
    file.read_exact(&mut header)
        .map_err(|error| format!("failed to read output header: {error}"))?;
    file.seek(SeekFrom::Start(0))
        .map_err(|error| format!("failed to reset output cursor: {error}"))?;
    match header {
        [0x66, 0x4c, 0x61, 0x43] => Ok("flac"),
        [0x49, 0x44, 0x33, _] => Ok("mp3"),
        _ => Ok(UNKNOWN_EXTENSION),
    }
}

fn temporary_output_path(input_path: &Path) -> PathBuf {
    input_path.with_extension("ncmdump.tmp")
}

fn final_output_path(input_path: &Path, extension: &str) -> PathBuf {
    input_path.with_extension(extension)
}

fn throw_io_exception(env: &mut JNIEnv, message: &str) {
    let _ = env.throw_new("java/io/IOException", message);
}
