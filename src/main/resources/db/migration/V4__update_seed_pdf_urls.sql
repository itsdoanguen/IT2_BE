-- ==============================================================
-- V4: UPDATE V1 SEED REGULATIONS WITH REAL DUT.UDN.VN PDF URLS
-- ==============================================================

UPDATE regulations
SET document_id = '0018',
    pdf_url = 'http://dut.udn.vn/Files/admin/files/VanbanPhapquy/18.Quyet%20dinh_1414_QĐ-ĐHBK_Quy%20đinh%20ve%20xet%2C%20cap%20HB%20cho%20SV%20he%20ĐH%20chính%20quy%20%20tai%20Truong%20ĐHBK-ĐHĐN.pdf'
WHERE document_number = '1414/QĐ-ĐHBK' AND (pdf_url IS NULL OR pdf_url LIKE '/tai-lieu-riel/%');

UPDATE regulations
SET document_id = '0009',
    pdf_url = 'http://dut.udn.vn/Files/admin/files/VanbanPhapquy/9.VBHN_28_VBHN-BGDĐT_Ve%20hoc%20bong%20khuyen%20khich%20hoc%20tap.pdf'
WHERE document_number = '28/VBHN-BGDĐT' AND (pdf_url IS NULL OR pdf_url LIKE '/tai-lieu-riel/%');

UPDATE regulations
SET document_id = '0008',
    pdf_url = 'http://dut.udn.vn/Files/admin/files/VanbanPhapquy/8.Thong%20tu_35_2011_TT-BGDĐT_Quy%20dinh%20ve%20trao%20nhan%20hoc%20bong.pdf'
WHERE document_number = '35/2011/TT-BGDĐT' AND (pdf_url IS NULL OR pdf_url LIKE '/tai-lieu-riel/%');
