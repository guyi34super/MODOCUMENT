package com.modocument.doccreation.pdftemplate;

/**
 * The kind of a fill-in region. CHOICE (ws33) is a dropdown: its options
 * live in the region's FieldProps and its value is drawn as single-line text.
 */
public enum RegionKind { TEXT, MULTILINE, DATE, NUMBER, CHECKBOX, IMAGE, SIGNATURE, CHOICE }
