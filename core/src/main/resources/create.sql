CREATE TABLE IF NOT EXISTS `skinData` (
  `SkinID`      INTEGER PRIMARY KEY AUTO_INCREMENT,
  `DisplayName` VARCHAR(255),
  `Timestamp`   BIGINT        NOT NULL,
  `UUID`        CHAR(36)      NOT NULL,
  `Name`        VARCHAR(16)   NOT NULL,
  `SlimModel`   BIT DEFAULT 0 NOT NULL,
  `SkinURL`     VARCHAR(255)  NOT NULL,
  `CapeURL`     VARCHAR(255),
  `Signature`   BLOB          NOT NULL
);

CREATE TABLE IF NOT EXISTS `preferences` (
  `UserID`     INTEGER PRIMARY KEY AUTO_INCREMENT,
  `UUID`       CHAR(36) NOT NULL,
  `TargetSkin` INTEGER  NOT NULL,
  `KeepSkin`   BIT      NOT NULL   DEFAULT 1,
  UNIQUE (`UUID`),
  FOREIGN KEY (`TargetSkin`) REFERENCES `skinData` (`SkinID`)
    ON DELETE CASCADE
);
