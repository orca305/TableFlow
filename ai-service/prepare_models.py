from app.providers import LocalProvider

if __name__ == "__main__":
    # Pinned RapidOCR loads models lazily; initialize detection and recognition before accepting uploads.
    engine = LocalProvider().engine
    engine._load_det_model()
    engine._load_rec_model()
    print("Local OCR models are ready. No user files were uploaded.")
